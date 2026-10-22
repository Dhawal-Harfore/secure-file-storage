package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
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
        "spring.datasource.url=jdbc:h2:mem:shares;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class SharesTests {

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
    private JdbcTemplate jdbc;

    private JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private String upload(String name, String mime, byte[] data) throws Exception {
        String uploadId = json(mvc.perform(post("/api/files/initiate").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"mime\":\"" + mime + "\",\"size\":" + data.length + "}"))
                .andExpect(status().isOk()).andReturn()).get("uploadId").asString();
        mvc.perform(put("/api/files/" + uploadId + "/chunk/0").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(data))
                .andExpect(status().isOk());
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        return json(mvc.perform(post("/api/files/" + uploadId + "/complete").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sha256\":\"" + sha + "\"}"))
                .andExpect(status().isOk()).andReturn()).get("id").asString();
    }

    private JsonNode share(String fileId, String body) throws Exception {
        return json(mvc.perform(post("/api/files/" + fileId + "/shares").with(ASHA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private int viewsUsed(String shareId) {
        return jdbc.queryForObject("select views_used from shares where id = ?", Integer.class, shareId);
    }

    @Test
    void ownerCreatesListsAndRevokesALink() throws Exception {
        String fileId = upload("notes.txt", "text/plain", "plain notes".getBytes(StandardCharsets.UTF_8));

        JsonNode created = share(fileId, "{\"accessLevel\":\"viewer\",\"actions\":[\"view\",\"download\"],\"maxViews\":2}");
        String token = created.get("token").asString();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(created.get("actions").toString()).isEqualTo("[\"download\",\"view\"]");

        JsonNode listed = json(mvc.perform(get("/api/files/" + fileId + "/shares").with(ASHA))
                .andExpect(status().isOk()).andReturn());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("token").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select token_hash from shares where id = ?", String.class,
                created.get("id").asString())).doesNotContain(token).hasSize(64);

        mvc.perform(post("/api/shares/" + created.get("id").asString() + "/revoke").with(ASHA).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        mvc.perform(get("/api/s/" + token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.message").value("This link was revoked by its owner."));

        assertThat(jdbc.queryForObject("select count(*) from audit_events where share_id = ? and action in ('share','revoke')",
                Integer.class, created.get("id").asString())).isEqualTo(2);
    }

    @Test
    void publicLinkHidesTheOwnerAndCountsOneViewPerOpening() throws Exception {
        byte[] data = "0123456789 shared bytes".getBytes(StandardCharsets.UTF_8);
        String fileId = upload("shared.txt", "text/plain", data);
        JsonNode created = share(fileId, "{\"accessLevel\":\"viewer\",\"actions\":[\"view\",\"download\"],\"maxViews\":2}");
        String token = created.get("token").asString();
        String shareId = created.get("id").asString();

        mvc.perform(get("/api/s/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.file.name").value("shared.txt"))
                .andExpect(jsonPath("$.file.ownerEmail").doesNotExist())
                .andExpect(jsonPath("$.permit.accessLevel").value("viewer"))
                .andExpect(jsonPath("$.permit.qualityTier").value("original"));

        MockHttpServletResponse first = mvc.perform(get("/api/s/" + token + "/content"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(first.getContentAsByteArray()).isEqualTo(data);
        assertThat(viewsUsed(shareId)).isEqualTo(1);

        mvc.perform(get("/api/s/" + token + "/content").header("Range", "bytes=5-"))
                .andExpect(status().isPartialContent());
        assertThat(viewsUsed(shareId)).isEqualTo(1);

        mvc.perform(get("/api/s/" + token + "/content")).andExpect(status().isOk());
        assertThat(viewsUsed(shareId)).isEqualTo(2);

        mvc.perform(get("/api/s/" + token + "/content"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.message").value("This link has reached its view limit."));
        assertThat(viewsUsed(shareId)).isEqualTo(2);

        assertThat(jdbc.queryForObject("select count(*) from audit_events where share_id = ? and action = 'view'",
                Integer.class, shareId)).isEqualTo(2);
    }

    @Test
    void guestLinkOnAPlainFileShowsDetailsOnly() throws Exception {
        String fileId = upload("budget.csv", "text/csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        String token = share(fileId, "{\"accessLevel\":\"guest\"}").get("token").asString();

        mvc.perform(get("/api/s/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permit.qualityTier").value("metadata"))
                .andExpect(jsonPath("$.permit.keyHandle").doesNotExist());
        mvc.perform(get("/api/s/" + token + "/content"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("denied"));
    }

    @Test
    void expiredAndUnknownLinksAreRefused() throws Exception {
        String fileId = upload("old.txt", "text/plain", "old".getBytes(StandardCharsets.UTF_8));
        JsonNode created = share(fileId, "{\"accessLevel\":\"viewer\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\"}");
        jdbc.update("update shares set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), created.get("id").asString());

        mvc.perform(get("/api/s/" + created.get("token").asString()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.message").value("This link has expired."));
        mvc.perform(get("/api/s/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).andExpect(status().isNotFound());
        mvc.perform(get("/api/s/short")).andExpect(status().isNotFound());
    }

    @Test
    void linksAreValidated() throws Exception {
        String fileId = upload("v.txt", "text/plain", "v".getBytes(StandardCharsets.UTF_8));
        String[] bad = {
                "{\"accessLevel\":\"editor\"}",
                "{\"accessLevel\":\"guest\",\"actions\":[\"view\",\"download\"]}",
                "{\"accessLevel\":\"viewer\",\"actions\":[\"delete\"]}",
                "{\"accessLevel\":\"viewer\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}",
                "{\"accessLevel\":\"viewer\",\"maxViews\":0}",
        };
        for (String body : bad) {
            mvc.perform(post("/api/files/" + fileId + "/shares").with(ASHA).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid_share"));
        }
    }

    @Test
    void onlyPeopleWhoCanShareSeeOrMakeLinks() throws Exception {
        String fileId = upload("mine.txt", "text/plain", "mine".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/files/" + fileId + "/shares").with(RAVI)).andExpect(status().isNotFound());
        mvc.perform(post("/api/files/" + fileId + "/shares").with(RAVI).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accessLevel\":\"guest\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/files/" + fileId + "/shares")).andExpect(status().isUnauthorized());
    }
}
