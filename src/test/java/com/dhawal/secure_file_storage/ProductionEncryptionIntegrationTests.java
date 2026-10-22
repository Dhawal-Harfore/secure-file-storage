package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dhawal.secure_file_storage.ControlPlaneDoubles.TestAudit;
import com.dhawal.secure_file_storage.ControlPlaneDoubles.TestCatalog;
import com.dhawal.secure_file_storage.controlplane.FileCipher;
import com.dhawal.secure_file_storage.controlplane.LocalFileCipher;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "vault.storage.chunk-size=1KB",
        "vault.storage.max-upload-size=64KB",
        "vault.storage.free-space-reserve=0B",
        "vault.storage.max-active-uploads=50",
        "spring.datasource.url=jdbc:h2:mem:production-encryption;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@Import(ProductionEncryptionIntegrationTests.Doubles.class)
class ProductionEncryptionIntegrationTests {

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("vault.storage.root", () -> root.toString());
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RequestPostProcessor ASHA = user("asha@example.com");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestCatalog catalog;

    @Autowired
    private TestAudit audit;

    @Test
    void productionEncryptionSurvivesUploadCompletionAndDownload() throws Exception {
        Map<String, FileCipher> ciphers = context.getBeansOfType(FileCipher.class);
        assertThat(ciphers).hasSize(1);
        assertThat(ciphers.values()).hasOnlyElementsOfType(LocalFileCipher.class);

        byte[] original = bytes(2500, "PRODUCTION-GCM-PLAINTEXT-MARKER-");
        byte[] marker = "PRODUCTION-GCM-PLAINTEXT-MARKER-".getBytes(StandardCharsets.US_ASCII);
        String uploadId = initiate(original.length);

        putChunk(uploadId, 1, Arrays.copyOfRange(original, 1024, 2048));
        putChunk(uploadId, 0, Arrays.copyOfRange(original, 0, 1024));
        putChunk(uploadId, 2, Arrays.copyOfRange(original, 2048, original.length));

        MvcResult completed = mvc.perform(post("/api/files/" + uploadId + "/complete")
                        .with(ASHA).with(csrf())
                        .contentType("application/json")
                        .content("{\"sha256\":\"" + sha256(original) + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String fileId = JSON.readTree(completed.getResponse().getContentAsString()).get("id").asString();

        MvcResult downloaded = mvc.perform(get("/api/files/" + fileId + "/content").with(ASHA))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(downloaded.getResponse().getContentAsByteArray()).isEqualTo(original);
        assertThat(catalog.find(fileId)).isPresent();
        assertThat(audit.forFile(fileId)).extracting(event -> event.action() + ":" + event.result())
                .containsExactly("upload:permit", "view:permit");

        try (Stream<Path> files = Files.walk(root.resolve("files"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(contains(Files.readAllBytes(file), marker))
                        .as("encrypted storage file %s", file)
                        .isFalse();
            }
        }
    }

    private String initiate(long size) throws Exception {
        MvcResult result = mvc.perform(post("/api/files/initiate").with(ASHA).with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"production.bin\",\"mime\":\"application/octet-stream\",\"size\":"
                                + size + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("uploadId").asString();
    }

    private void putChunk(String uploadId, int index, byte[] data) throws Exception {
        mvc.perform(put("/api/files/" + uploadId + "/chunk/" + index).with(ASHA).with(csrf())
                        .contentType("application/octet-stream")
                        .content(data))
                .andExpect(status().isOk());
    }

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

    private static boolean contains(byte[] haystack, byte[] needle) {
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

    @TestConfiguration
    static class Doubles {

        @Bean
        @Primary
        TestCatalog testCatalog() {
            return new TestCatalog();
        }

        @Bean
        @Primary
        ControlPlaneDoubles.TestPermits testPermits(TestCatalog catalog) {
            return new ControlPlaneDoubles.TestPermits(catalog);
        }

        @Bean
        @Primary
        TestAudit testAudit() {
            return new TestAudit();
        }
    }
}
