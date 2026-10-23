package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "vault.storage.chunk-size=1KB",
        "vault.storage.max-upload-size=64KB",
        "vault.storage.free-space-reserve=0B",
        "vault.storage.max-active-uploads=50",
        "spring.datasource.url=jdbc:h2:mem:access;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AccessManagementTests {

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("vault.storage.root", () -> root.toString());
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RequestPostProcessor OWNER = user("owner@example.com");
    private static final RequestPostProcessor RAVI = user("ravi@example.com");
    private static final RequestPostProcessor MEERA = user("meera@example.com");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private String upload(String name, String mime, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        String uploadId = json(mvc.perform(post("/api/files/initiate").with(OWNER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"mime\":\"" + mime + "\",\"size\":" + data.length + "}"))
                .andExpect(status().isOk()).andReturn()).get("uploadId").asString();
        mvc.perform(put("/api/files/" + uploadId + "/chunk/0").with(OWNER).with(csrf())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(data)).andExpect(status().isOk());
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        return json(mvc.perform(post("/api/files/" + uploadId + "/complete").with(OWNER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sha256\":\"" + sha + "\"}"))
                .andExpect(status().isOk()).andReturn()).get("id").asString();
    }

    private String grant(String fileId, String email, String level, boolean download) throws Exception {
        return json(mvc.perform(post("/api/files/" + fileId + "/people").with(OWNER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"accessLevel\":\"" + level + "\",\"download\":" + download + "}"))
                .andExpect(status().isOk()).andReturn()).get("id").asString();
    }

    private JsonNode fileAs(RequestPostProcessor who, String fileId) throws Exception {
        return json(mvc.perform(get("/api/files/" + fileId).with(who)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void ownerRenamesAFileAndTheNameIsCleaned() throws Exception {
        String fileId = upload("draft.txt", "text/plain", "draft");

        mvc.perform(patch("/api/files/" + fileId).with(OWNER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"../final notes.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("final notes.txt"));

        mvc.perform(patch("/api/files/" + fileId).with(OWNER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
        assertThat(fileAs(OWNER, fileId).get("name").asString()).isEqualTo("final notes.txt");
    }

    @Test
    void deleteShredsKeysRemovesBytesRevokesLinksAndKeepsHistory() throws Exception {
        String fileId = upload("old.txt", "text/plain", "remove me");
        String token = json(mvc.perform(post("/api/files/" + fileId + "/shares").with(OWNER).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accessLevel\":\"viewer\",\"actions\":[\"view\",\"download\"]}"))
                .andExpect(status().isOk()).andReturn()).get("token").asString();
        long keysBefore = keyFiles();

        mvc.perform(delete("/api/files/" + fileId).with(OWNER).with(csrf())).andExpect(status().isNoContent());

        mvc.perform(get("/api/files/" + fileId).with(OWNER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/files/" + fileId + "/content").with(OWNER)).andExpect(status().isNotFound());
        assertThat(json(mvc.perform(get("/api/files").with(OWNER)).andReturn()).findValuesAsString("id")).doesNotContain(fileId);
        mvc.perform(get("/api/s/" + token)).andExpect(status().isGone());
        assertThat(root.resolve("files").resolve(fileId)).doesNotExist();
        assertThat(keyFiles()).isEqualTo(keysBefore - 1);
        assertThat(jdbc.queryForObject("select count(*) from audit_events where file_id = ? and action in ('upload','share','delete')",
                Integer.class, fileId)).isEqualTo(3);
    }

    @Test
    void peopleGetExactlyTheirLevel() throws Exception {
        String fileId = upload("plan.txt", "text/plain", "plan");

        mvc.perform(get("/api/files/" + fileId).with(RAVI)).andExpect(status().isNotFound());

        String raviGrant = grant(fileId, "Ravi@Example.com", "viewer", false);
        JsonNode asViewer = fileAs(RAVI, fileId);
        assertThat(asViewer.at("/permit/accessLevel").asString()).isEqualTo("viewer");
        assertThat(asViewer.at("/permit/qualityTier").asString()).isEqualTo("metadata");
        mvc.perform(get("/api/files/" + fileId + "/content").with(RAVI)).andExpect(status().isForbidden());

        grant(fileId, "ravi@example.com", "viewer", true);
        assertThat(fileAs(RAVI, fileId).at("/permit/qualityTier").asString()).isEqualTo("original");
        mvc.perform(get("/api/files/" + fileId + "/content").with(RAVI)).andExpect(status().isOk());
        mvc.perform(patch("/api/files/" + fileId).with(RAVI).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x.txt\"}")).andExpect(status().isForbidden());

        grant(fileId, "meera@example.com", "editor", false);
        mvc.perform(patch("/api/files/" + fileId).with(MEERA).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"plan v2.txt\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/files/" + fileId).with(MEERA).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/files/" + fileId + "/people").with(MEERA).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.com\",\"accessLevel\":\"viewer\"}"))
                .andExpect(status().isForbidden());

        JsonNode people = json(mvc.perform(get("/api/files/" + fileId + "/people").with(OWNER)).andReturn());
        assertThat(people.findValuesAsString("email")).containsExactlyInAnyOrder("ravi@example.com", "meera@example.com");

        mvc.perform(delete("/api/files/" + fileId + "/people/" + raviGrant).with(OWNER).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/files/" + fileId).with(RAVI)).andExpect(status().isNotFound());
    }

    @Test
    void grantsAreValidated() throws Exception {
        String fileId = upload("v.txt", "text/plain", "v");
        String[] bad = {
                "{\"email\":\"owner@example.com\",\"accessLevel\":\"viewer\"}",
                "{\"email\":\"not-an-email\",\"accessLevel\":\"viewer\"}",
                "{\"email\":\"a@example.com\",\"accessLevel\":\"owner\"}",
        };
        for (String body : bad) {
            mvc.perform(post("/api/files/" + fileId + "/people").with(OWNER).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid_person"));
        }
    }

    @Test
    void auditShowsYourFilesAndYourOwnActions() throws Exception {
        String fileId = upload("audited.txt", "text/plain", "audited");
        grant(fileId, "ravi@example.com", "viewer", true);
        mvc.perform(get("/api/files/" + fileId + "/content").with(RAVI)).andExpect(status().isOk());

        JsonNode owner = json(mvc.perform(get("/api/audit").with(OWNER).param("fileId", fileId))
                .andExpect(status().isOk()).andReturn());
        assertThat(owner.get("events").findValuesAsString("actorEmail")).contains("owner@example.com", "ravi@example.com");
        assertThat(owner.get("events").get(0).get("at").asString())
                .isGreaterThanOrEqualTo(owner.get("events").get(owner.get("events").size() - 1).get("at").asString());

        JsonNode uploads = json(mvc.perform(get("/api/audit").with(OWNER).param("action", "upload").param("pageSize", "1"))
                .andReturn());
        assertThat(uploads.get("events")).hasSize(1);
        assertThat(uploads.get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(uploads.get("pageSize").asInt()).isEqualTo(1);

        JsonNode ravi = json(mvc.perform(get("/api/audit").with(RAVI)).andReturn());
        assertThat(ravi.get("events").findValuesAsString("actorEmail")).containsOnly("ravi@example.com");

        mvc.perform(get("/api/audit").param("from", "yesterday").with(OWNER)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());
    }

    private static long keyFiles() throws Exception {
        Path keys = root.resolve(".keys");
        if (!Files.isDirectory(keys)) {
            return 0;
        }
        try (Stream<Path> list = Files.list(keys)) {
            return list.filter(p -> p.toString().endsWith(".key")).count();
        }
    }
}
