package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dhawal.secure_file_storage.ControlPlaneDoubles.TestAudit;
import com.dhawal.secure_file_storage.ControlPlaneDoubles.TestCatalog;
import com.dhawal.secure_file_storage.ControlPlaneDoubles.TestPermits;
import com.dhawal.secure_file_storage.controlplane.QualityTier;
import com.dhawal.secure_file_storage.controlplane.Rendition;
import com.dhawal.secure_file_storage.controlplane.StoredFile;
import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.rendition.RenditionJobs;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "vault.storage.chunk-size=256KB",
        "vault.storage.max-upload-size=50MB",
        "vault.storage.free-space-reserve=0B",
        "vault.storage.max-active-uploads=50"
})
@AutoConfigureMockMvc
@Import(ControlPlaneDoubles.class)
class RenditionTests {

    @TempDir
    static Path root;

    @TempDir
    static Path media;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("vault.storage.root", () -> root.toString());
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RequestPostProcessor ASHA = user("asha@example.com");
    private static final RequestPostProcessor RAVI = user("ravi@example.com");
    private static final String RAVI_ID = SessionUser.of("ravi@example.com").id();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestCatalog catalog;

    @Autowired
    private TestPermits permits;

    @Autowired
    private TestAudit audit;

    @Autowired
    private RenditionJobs jobs;

    static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("%s: %s", String.join(" ", command), output).isZero();
        return output;
    }

    @BeforeAll
    static void makeMedia() throws Exception {
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=d=2:s=1280x720:r=25",
                "-f", "lavfi", "-i", "sine=d=2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-shortest", "-y", media.resolve("video.mp4").toString());
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=d=0.5:s=320x240:r=10",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", media.resolve("short.mp4").toString());
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=black@0.0:s=1600x900,format=rgba,drawbox=x=400:y=300:w=400:h=200:c=blue:t=fill:replace=1",
                "-frames:v", "1", "-y", media.resolve("image.png").toString());
        Files.writeString(media.resolve("doc.ps"), "%!PS\n/Helvetica findfont 40 scalefont setfont\n"
                + "80 600 moveto (Page one secret) show showpage\n80 600 moveto (Page two secret) show showpage\n");
        run("gs", "-q", "-dSAFER", "-dBATCH", "-dNOPAUSE", "-sDEVICE=pdfwrite",
                "-sOutputFile=" + media.resolve("doc.pdf"), media.resolve("doc.ps").toString());
        Files.writeString(media.resolve("evil.mp4"),
                "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:10.0,\nfile:///etc/passwd\n#EXT-X-ENDLIST\n");
    }

    private String upload(String name, String mime) throws Exception {
        byte[] data = Files.readAllBytes(media.resolve(name));
        MvcResult started = mvc.perform(post("/api/files/initiate").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"mime\":\"" + mime + "\",\"size\":" + data.length + "}"))
                .andExpect(status().isOk()).andReturn();
        String uploadId = JSON.readTree(started.getResponse().getContentAsString()).get("uploadId").asString();
        int chunk = 256 * 1024;
        for (int i = 0; i * chunk < data.length; i++) {
            mvc.perform(put("/api/files/" + uploadId + "/chunk/" + i).with(ASHA).with(csrf())
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .content(Arrays.copyOfRange(data, i * chunk, Math.min(data.length, (i + 1) * chunk))))
                    .andExpect(status().isOk());
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        MvcResult done = mvc.perform(post("/api/files/" + uploadId + "/complete").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sha256\":\"" + sha + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String fileId = JSON.readTree(done.getResponse().getContentAsString()).get("id").asString();
        assertThat(jobs.awaitIdle(Duration.ofMinutes(3))).isTrue();
        return fileId;
    }

    private byte[] fetch(RequestPostProcessor who, String fileId, String tier, String expectTier) throws Exception {
        return mvc.perform(get("/api/files/" + fileId + "/content").with(who).param("tier", tier))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Quality-Tier", expectTier))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static String probe(byte[] data, String suffix) throws Exception {
        Path file = Files.createTempFile(media, "probe-", suffix);
        Files.write(file, data);
        return run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height",
                "-of", "csv=p=0", file.toString()).strip();
    }

    private static BufferedImage image(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private Map<QualityTier, Rendition> renditions(String fileId) {
        return catalog.find(fileId).orElseThrow().renditions();
    }

    @Test
    void videoGetsAPosterAndAVp9Copy() throws Exception {
        String fileId = upload("video.mp4", "video/mp4");

        assertThat(renditions(fileId)).containsKeys(QualityTier.ORIGINAL, QualityTier.REDUCED, QualityTier.LOWEST);
        assertThat(renditions(fileId).get(QualityTier.REDUCED).mime()).isEqualTo("video/webm");
        assertThat(renditions(fileId).get(QualityTier.LOWEST).mime()).isEqualTo("image/jpeg");

        List<String> streams = probe(fetch(ASHA, fileId, "reduced", "reduced"), ".webm").lines().toList();
        assertThat(streams).contains("opus");
        String[] video = streams.stream().filter(s -> s.startsWith("vp9,")).findFirst().orElseThrow().split(",");
        assertThat(Integer.parseInt(video[1])).isEqualTo(720);
        assertThat(Integer.parseInt(video[2])).isBetween(404, 540).matches(h -> h % 2 == 0, "even");

        assertThat(image(fetch(ASHA, fileId, "lowest", "lowest")).getWidth()).isEqualTo(480);
        assertThat(root.resolve("work").resolve(fileId)).doesNotExist();
        assertThat(root.resolve("jobs").resolve(fileId + ".json")).doesNotExist();
    }

    @Test
    void veryShortVideoStillGetsAPoster() throws Exception {
        String fileId = upload("short.mp4", "video/mp4");

        BufferedImage poster = image(fetch(ASHA, fileId, "lowest", "lowest"));
        assertThat(poster.getWidth()).isEqualTo(320);
        assertThat(probe(fetch(ASHA, fileId, "reduced", "reduced"), ".webm")).isEqualTo("vp9,320,240");
    }

    @Test
    void imagesAreScaledDownAndFlattenedOntoWhite() throws Exception {
        String fileId = upload("image.png", "image/png");

        BufferedImage reduced = image(fetch(ASHA, fileId, "reduced", "reduced"));
        assertThat(reduced.getWidth()).isEqualTo(1280);
        assertThat(reduced.getHeight()).isEqualTo(720);
        int corner = reduced.getRGB(10, 10);
        int box = reduced.getRGB(480, 320);
        assertThat(corner & 0xff).as("corner blue channel").isGreaterThan(240);
        assertThat((corner >> 16) & 0xff).as("corner red channel").isGreaterThan(240);
        assertThat((box >> 16) & 0xff).as("box red channel").isLessThan(30);
        assertThat(box & 0xff).as("box blue channel").isGreaterThan(200);

        assertThat(image(fetch(ASHA, fileId, "lowest", "lowest")).getWidth()).isEqualTo(480);
    }

    @Test
    void pdfReducedHasNoTextAndLowestIsTheFirstPage() throws Exception {
        String fileId = upload("doc.pdf", "application/pdf");

        assertThat(run("gs", "-q", "-dSAFER", "-dBATCH", "-dNOPAUSE", "-sDEVICE=txtwrite", "-sOutputFile=-",
                media.resolve("doc.pdf").toString())).contains("secret");

        byte[] reduced = fetch(ASHA, fileId, "reduced", "reduced");
        Path reducedFile = Files.write(media.resolve("reduced.pdf"), reduced);
        assertThat(new String(reduced, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(run("gs", "-q", "-dSAFER", "-dBATCH", "-dNOPAUSE", "-sDEVICE=txtwrite", "-sOutputFile=-",
                reducedFile.toString())).doesNotContain("secret");

        BufferedImage firstPage = image(fetch(ASHA, fileId, "lowest", "lowest"));
        assertThat(firstPage.getHeight()).isGreaterThan(firstPage.getWidth());
    }

    @Test
    void permitsCapTheQualityServed() throws Exception {
        String fileId = upload("image.png", "image/png");

        permits.grant(fileId, RAVI_ID, "viewer", QualityTier.REDUCED, "view");
        assertThat(image(fetch(RAVI, fileId, null, "reduced")).getWidth()).isEqualTo(1280);
        assertThat(image(fetch(RAVI, fileId, "original", "reduced")).getWidth()).isEqualTo(1280);
        assertThat(image(fetch(RAVI, fileId, "lowest", "lowest")).getWidth()).isEqualTo(480);

        permits.grant(fileId, RAVI_ID, "guest", QualityTier.LOWEST, "view");
        assertThat(image(fetch(RAVI, fileId, "original", "lowest")).getWidth()).isEqualTo(480);
        byte[] thumb = mvc.perform(get("/api/files/" + fileId + "/thumb").with(RAVI))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(image(thumb).getWidth()).isEqualTo(480);

        assertThat(audit.forFile(fileId))
                .filteredOn(e -> RAVI_ID.equals(e.actorId()))
                .extracting(e -> e.qualityTier().wire())
                .containsExactly("reduced", "reduced", "lowest", "lowest");
    }

    @Test
    void hostileVideoGetsNoRenditionsAndNothingLeaks() throws Exception {
        String fileId = upload("evil.mp4", "video/mp4");

        assertThat(renditions(fileId)).containsOnlyKeys(QualityTier.ORIGINAL);
        assertThat(root.resolve("jobs").resolve(fileId + ".json")).doesNotExist();

        permits.grant(fileId, RAVI_ID, "viewer", QualityTier.REDUCED, "view");
        mvc.perform(get("/api/files/" + fileId + "/content").with(RAVI))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/files/" + fileId + "/thumb").with(ASHA))
                .andExpect(status().isNotFound());
    }

    @Test
    void unfinishedJobsResumeAfterARestart() throws Exception {
        String fileId = upload("image.png", "image/png");
        StoredFile done = catalog.find(fileId).orElseThrow();

        catalog.add(new StoredFile(done.id(), done.name(), done.mime(), done.size(), done.kind(), done.ownerId(),
                done.ownerEmail(), done.createdAt(), done.updatedAt(),
                Map.of(QualityTier.ORIGINAL, done.renditions().get(QualityTier.ORIGINAL))));
        Files.writeString(root.resolve("jobs").resolve(fileId + ".json"),
                "{\"fileId\":\"" + fileId + "\",\"attempts\":1}");

        jobs.resumeUnfinished();
        assertThat(jobs.awaitIdle(Duration.ofMinutes(2))).isTrue();

        assertThat(renditions(fileId)).containsKeys(QualityTier.REDUCED, QualityTier.LOWEST);
        assertThat(root.resolve("jobs").resolve(fileId + ".json")).doesNotExist();
    }

    @Test
    void jobsGiveUpAfterThreeAttempts() throws Exception {
        String fileId = upload("image.png", "image/png");
        StoredFile done = catalog.find(fileId).orElseThrow();
        catalog.add(new StoredFile(done.id(), done.name(), done.mime(), done.size(), done.kind(), done.ownerId(),
                done.ownerEmail(), done.createdAt(), done.updatedAt(),
                Map.of(QualityTier.ORIGINAL, done.renditions().get(QualityTier.ORIGINAL))));
        Files.writeString(root.resolve("jobs").resolve(fileId + ".json"),
                "{\"fileId\":\"" + fileId + "\",\"attempts\":3}");

        jobs.resumeUnfinished();
        assertThat(jobs.awaitIdle(Duration.ofMinutes(1))).isTrue();

        assertThat(renditions(fileId)).containsOnlyKeys(QualityTier.ORIGINAL);
        assertThat(root.resolve("jobs").resolve(fileId + ".json")).doesNotExist();
    }
}
