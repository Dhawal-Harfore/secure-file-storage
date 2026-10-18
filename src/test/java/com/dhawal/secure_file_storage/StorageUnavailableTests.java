package com.dhawal.secure_file_storage;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StorageUnavailableTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void withoutTheControlPlaneFileCallsSay503() throws Exception {
        mvc.perform(post("/api/files/initiate").with(user("asha@example.com")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a.txt\",\"mime\":\"text/plain\",\"size\":5}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("unavailable"))
                .andExpect(jsonPath("$.error.message").value("File storage isn't connected to the encryption service yet."));

        mvc.perform(get("/api/files/f_0123456789abcdef/content").with(user("asha@example.com")))
                .andExpect(status().isServiceUnavailable());
    }
}
