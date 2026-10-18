package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

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
import com.dhawal.secure_file_storage.ControlPlaneDoubles.TestCipher;
import com.dhawal.secure_file_storage.controlplane.QualityTier;
import com.dhawal.secure_file_storage.controlplane.StoredFile;
import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.storage.Ids;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "vault.storage.chunk-size=1KB",
        "vault.storage.max-upload-size=64KB",
        "vault.storage.free-space-reserve=0B",
        "vault.storage.max-active-uploads=50"
})
@AutoConfigureMockMvc
@Import(ControlPlaneDoubles.class)
class StorageTests {

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("vault.storage.root", () -> root.toString());
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RequestPostProcessor ASHA = user("asha@example.com");
    private static final RequestPostProcessor RAVI = user("ravi@example.com");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestCipher cipher;

    @Autowired
    private TestCatalog catalog;

    @Autowired
    private TestAudit audit;

    @Autowired
    private ControlPlaneDoubles.TestPermits permits;

    private static byte[] bytes(int size, String marker) {
        byte[] data = new byte[size];
        byte[] pattern = marker.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < size; i++) {
            data[i] = pattern[i % pattern.length];
        }
        return data;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private String initiate(RequestPostProcessor who, String name, String mime, long size) throws Exception {
        MvcResult result = mvc.perform(post("/api/files/initiate").with(who).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"mime\":\"" + mime + "\",\"size\":" + size + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkSize").value(1024))
                .andExpect(jsonPath("$.received").isEmpty())
                .andReturn();
        return json(result).get("uploadId").asString();
    }

    private MvcResult putChunk(RequestPostProcessor who, String uploadId, int index, byte[] data) throws Exception {
        return mvc.perform(put("/api/files/" + uploadId + "/chunk/" + index).with(who).with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(data))
                .andReturn();
    }

    private String upload(RequestPostProcessor who, String name, String mime, byte[] data) throws Exception {
        String uploadId = initiate(who, name, mime, data.length);
        for (int i = 0; i * 1024 < data.length; i++) {
            byte[] chunk = Arrays.copyOfRange(data, i * 1024, Math.min(data.length, (i + 1) * 1024));
            assertThat(putChunk(who, uploadId, i, chunk).getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult done = mvc.perform(post("/api/files/" + uploadId + "/complete").with(who).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sha256\":\"" + sha256(data) + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json(done).get("id").asString();
    }

    private List<Path> allFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    void uploadsInChunksOutOfOrderAndDownloadsTheSameBytes() throws Exception {
        byte[] data = bytes(2500, "chunked-upload-");
        String uploadId = initiate(ASHA, "notes.bin", "application/octet-stream", data.length);

        assertThat(json(putChunk(ASHA, uploadId, 2, Arrays.copyOfRange(data, 2048, 2500))).get("received").toString())
                .isEqualTo("[2]");
        assertThat(json(putChunk(ASHA, uploadId, 0, Arrays.copyOfRange(data, 0, 1024))).get("received").toString())
                .isEqualTo("[0,2]");

        mvc.perform(get("/api/files/" + uploadId).with(ASHA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received[0]").value(0))
                .andExpect(jsonPath("$.received[1]").value(2));

        putChunk(ASHA, uploadId, 1, Arrays.copyOfRange(data, 1024, 2048));

        MvcResult done = mvc.perform(post("/api/files/" + uploadId + "/complete").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sha256\":\"" + sha256(data).toUpperCase() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("notes.bin"))
                .andExpect(jsonPath("$.kind").value("other"))
                .andExpect(jsonPath("$.size").value(2500))
                .andExpect(jsonPath("$.ownerEmail").value("asha@example.com"))
                .andExpect(jsonPath("$.renditions[0]").value("original"))
                .andExpect(jsonPath("$.permit.accessLevel").value("owner"))
                .andReturn();
        String fileId = json(done).get("id").asString();
        assertThat(fileId).matches(Ids.FILE);

        MvcResult content = mvc.perform(get("/api/files/" + fileId + "/content").with(ASHA))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Quality-Tier", "original"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andReturn();
        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(data);

        assertThat(audit.forFile(fileId)).extracting(e -> e.action() + ":" + e.result())
                .containsExactly("upload:permit", "view:permit");
        assertThat(cipher.destroyed).contains(Ids.stagingKeyHandle(uploadId));
        assertThat(root.resolve("staging").resolve(uploadId)).doesNotExist();
    }

    @Test
    void plaintextNeverReachesTheDisk() throws Exception {
        byte[] secret = bytes(3000, "TOP-SECRET-PAYLOAD-");
        byte[] marker = "TOP-SECRET-PAYLOAD-".getBytes(StandardCharsets.US_ASCII);

        String uploadId = initiate(ASHA, "secret.bin", "application/octet-stream", secret.length);
        putChunk(ASHA, uploadId, 0, Arrays.copyOfRange(secret, 0, 1024));
        for (Path file : allFiles()) {
            assertThat(contains(Files.readAllBytes(file), marker)).as("staged %s", file).isFalse();
        }
        mvc.perform(delete("/api/files/" + uploadId).with(ASHA).with(csrf())).andExpect(status().isNoContent());

        upload(ASHA, "secret.bin", "application/octet-stream", secret);
        for (Path file : allFiles()) {
            assertThat(contains(Files.readAllBytes(file), marker)).as("stored %s", file).isFalse();
        }
    }

    @Test
    void rangeRequestsReturnTheRightSlice() throws Exception {
        byte[] data = bytes(3000, "0123456789abcdefghij");
        String fileId = upload(ASHA, "range.bin", "application/octet-stream", data);

        MvcResult middle = mvc.perform(get("/api/files/" + fileId + "/content").with(ASHA)
                        .header("Range", "bytes=1000-1999"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 1000-1999/3000"))
                .andReturn();
        assertThat(middle.getResponse().getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(data, 1000, 2000));

        MvcResult suffix = mvc.perform(get("/api/files/" + fileId + "/content").with(ASHA)
                        .header("Range", "bytes=-17"))
                .andExpect(status().isPartialContent())
                .andReturn();
        assertThat(suffix.getResponse().getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(data, 2983, 3000));

        MvcResult open = mvc.perform(get("/api/files/" + fileId + "/content").with(ASHA)
                        .header("Range", "bytes=2995-"))
                .andExpect(status().isPartialContent())
                .andReturn();
        assertThat(open.getResponse().getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(data, 2995, 3000));

        mvc.perform(get("/api/files/" + fileId + "/content").with(ASHA).header("Range", "bytes=3000-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */3000"));

        assertThat(audit.forFile(fileId)).filteredOn(e -> e.action().equals("view")).isEmpty();
    }

    @Test
    void wrongChunkLengthIsRejected() throws Exception {
        String uploadId = initiate(ASHA, "a.bin", "application/octet-stream", 2048);

        MvcResult shortChunk = putChunk(ASHA, uploadId, 0, new byte[1000]);
        assertThat(shortChunk.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(shortChunk).at("/error/code").asString()).isEqualTo("invalid_chunk");

        MvcResult longChunk = putChunk(ASHA, uploadId, 1, new byte[1500]);
        assertThat(longChunk.getResponse().getStatus()).isEqualTo(400);

        MvcResult outside = putChunk(ASHA, uploadId, 2, new byte[10]);
        assertThat(outside.getResponse().getStatus()).isEqualTo(400);

        mvc.perform(get("/api/files/" + uploadId).with(ASHA)).andExpect(jsonPath("$.received").isEmpty());
    }

    @Test
    void completingEarlyOrWithTheWrongChecksumFails() throws Exception {
        byte[] data = bytes(2048, "checksum-");
        String uploadId = initiate(ASHA, "c.bin", "application/octet-stream", data.length);
        putChunk(ASHA, uploadId, 0, Arrays.copyOfRange(data, 0, 1024));

        mvc.perform(post("/api/files/" + uploadId + "/complete").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sha256\":\"" + sha256(data) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("incomplete_upload"));

        putChunk(ASHA, uploadId, 1, Arrays.copyOfRange(data, 1024, 2048));
        int filesBefore = catalog.files.size();

        mvc.perform(post("/api/files/" + uploadId + "/complete").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sha256\":\"" + sha256(new byte[] {1}) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("checksum_mismatch"));

        assertThat(catalog.files).hasSize(filesBefore);
        assertThat(cipher.destroyed).contains(Ids.stagingKeyHandle(uploadId));
        mvc.perform(get("/api/files/" + uploadId).with(ASHA)).andExpect(status().isNotFound());

        mvc.perform(post("/api/files/" + initiate(ASHA, "d.bin", "", 10) + "/complete").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sha256\":\"not-hex\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_upload"));
    }

    @Test
    void abortDiscardsTheUploadAndItsKey() throws Exception {
        String uploadId = initiate(ASHA, "gone.bin", "application/octet-stream", 1500);
        putChunk(ASHA, uploadId, 0, new byte[1024]);

        mvc.perform(delete("/api/files/" + uploadId).with(ASHA).with(csrf())).andExpect(status().isNoContent());

        assertThat(root.resolve("staging").resolve(uploadId)).doesNotExist();
        assertThat(cipher.destroyed).contains(Ids.stagingKeyHandle(uploadId));
        assertThat(putChunk(ASHA, uploadId, 1, new byte[476]).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void someoneElsesUploadLooksLikeItDoesNotExist() throws Exception {
        String uploadId = initiate(ASHA, "mine.bin", "application/octet-stream", 100);

        assertThat(putChunk(RAVI, uploadId, 0, new byte[100]).getResponse().getStatus()).isEqualTo(404);
        mvc.perform(get("/api/files/" + uploadId).with(RAVI)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/files/" + uploadId).with(RAVI).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/files/" + uploadId).with(ASHA)).andExpect(status().isOk());
    }

    @Test
    void initiateValidatesTheFile() throws Exception {
        mvc.perform(post("/api/files/initiate").with(ASHA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"big.bin\",\"mime\":\"application/octet-stream\",\"size\":70000}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("too_large"));

        mvc.perform(post("/api/files/initiate").with(ASHA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"empty.bin\",\"mime\":\"text/plain\",\"size\":0}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/files/initiate").with(ASHA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"../../\",\"mime\":\"text/plain\",\"size\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_upload"));

        mvc.perform(post("/api/files/initiate").with(ASHA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x.txt\",\"mime\":\"text plain<script>\",\"size\":5}"))
                .andExpect(status().isBadRequest());

        String uploadId = initiate(ASHA, "../../etc/passwd", "Text/Plain; charset=utf-8", 5);
        byte[] data = "hello".getBytes(StandardCharsets.US_ASCII);
        putChunk(ASHA, uploadId, 0, data);
        MvcResult done = mvc.perform(post("/api/files/" + uploadId + "/complete").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sha256\":\"" + sha256(data) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("passwd"))
                .andExpect(jsonPath("$.mime").value("text/plain"))
                .andReturn();
        assertThat(json(done).get("id").asString()).matches(Ids.FILE);
    }

    @Test
    void activeHtmlIsServedAsADownloadNotAPage() throws Exception {
        byte[] html = "<script>alert(document.cookie)</script>".getBytes(StandardCharsets.US_ASCII);
        String fileId = upload(ASHA, "page.html", "text/html", html);

        mvc.perform(get("/api/files/" + fileId + "/content").with(ASHA))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));

        mvc.perform(get("/api/files/" + fileId + "/thumb").with(ASHA))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherPeopleNeedAPermit() throws Exception {
        String fileId = upload(ASHA, "private.bin", "application/octet-stream", bytes(100, "p"));
        String raviId = SessionUser.of("ravi@example.com").id();

        mvc.perform(get("/api/files/" + fileId + "/content").with(RAVI))
                .andExpect(status().isNotFound());

        permits.grant(fileId, raviId, "guest", QualityTier.METADATA, "view");
        mvc.perform(get("/api/files/" + fileId + "/content").with(RAVI))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("denied"))
                .andExpect(jsonPath("$.error.reason").value("policy:guest"));
        assertThat(audit.forFile(fileId)).extracting(e -> e.action() + ":" + e.result()).contains("view:deny");

        permits.grant(fileId, raviId, "viewer", QualityTier.ORIGINAL, "view", "download");
        mvc.perform(get("/api/files/" + fileId + "/content").with(RAVI)).andExpect(status().isOk());

        mvc.perform(get("/api/files/" + fileId + "/content").with(RAVI).param("tier", "best"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_tier"));
    }

    @Test
    void signedOutCallsGet401() throws Exception {
        mvc.perform(post("/api/files/initiate").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"mime\":\"text/plain\",\"size\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthenticated"));
        mvc.perform(get("/api/files/f_0123456789abcdef/content")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownFilesAndBadIdsAre404() throws Exception {
        mvc.perform(get("/api/files/f_0123456789abcdef/content").with(ASHA)).andExpect(status().isNotFound());
        mvc.perform(get("/api/files/up_aaaaaaaaaaaaaaaaaaaa").with(ASHA)).andExpect(status().isNotFound());
        assertThat(catalog.find("f_0123456789abcdef")).isEmpty();
        StoredFile any = catalog.files.values().stream().findFirst().orElse(null);
        assertThat(any == null || any.id().matches(Ids.FILE.pattern())).isTrue();
    }
}
