package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTests {

    private static final AtomicInteger clients = new AtomicInteger(1);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MagicLinkMailer mailer;

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String newClient() {
        return "10.0.0." + clients.getAndIncrement();
    }

    private String requestLink(String email, String ip) throws Exception {
        mvc.perform(post("/api/auth/magic-link").with(from(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ok").value(true));

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(email.strip().toLowerCase()), token.capture());
        return token.getValue();
    }

    private MvcResult verifyToken(String token) throws Exception {
        return mvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andReturn();
    }

    @Test
    void linkSignsInAndStartsSession() throws Exception {
        String token = requestLink("asha@example.com", newClient());

        MvcResult verified = verifyToken(token);
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        assertThat(verified.getResponse().getContentAsString())
                .contains("\"email\":\"asha@example.com\"")
                .containsPattern("\"id\":\"u_[0-9a-f]{8}\"");

        MockHttpSession session = (MockHttpSession) verified.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("asha@example.com"));
    }

    @Test
    void emailIsNormalisedBeforeSending() throws Exception {
        String token = requestLink("  Ravi@Example.COM ", newClient());

        mvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("ravi@example.com"));
    }

    @Test
    void linkWorksOnlyOnce() throws Exception {
        String token = requestLink("once@example.com", newClient());

        assertThat(verifyToken(token).getResponse().getStatus()).isEqualTo(200);

        mvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("gone"));
    }

    @Test
    void unknownTokenIsBadRequest() throws Exception {
        mvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-real-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_token"));
    }

    @Test
    void missingBodyIsBadRequestInContractShape() throws Exception {
        mvc.perform(post("/api/auth/verify"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_token"));

        mvc.perform(post("/api/auth/magic-link").with(from(newClient())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_email"));
    }

    @Test
    void malformedEmailIsRejectedWithoutSending() throws Exception {
        mvc.perform(post("/api/auth/magic-link").with(from(newClient()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-address\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_email"));

        verify(mailer, never()).send(anyString(), anyString());
    }

    @Test
    void emailProviderFailureIsReportedWithoutClaimingSuccess() throws Exception {
        doThrow(new MagicLinkMailer.MailDeliveryException("Email provider refused the request"))
                .when(mailer).send(anyString(), anyString());

        mvc.perform(post("/api/auth/magic-link").with(from(newClient()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"asha@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("email_unavailable"));
    }

    @Test
    void sessionWithoutSignInIsUnauthenticated() throws Exception {
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthenticated"));
    }

    @Test
    void otherApiCallsWithoutSignInGetContract401() throws Exception {
        mvc.perform(get("/api/files"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthenticated"));
    }

    @Test
    void logoutEndsSession() throws Exception {
        String token = requestLink("leave@example.com", newClient());
        MockHttpSession session = (MockHttpSession) verifyToken(token).getRequest().getSession(false);

        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();

        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addressIsThrottledAfterThreeLinks() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/magic-link").with(from(newClient()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"busy@example.com\"}"))
                    .andExpect(status().isAccepted());
        }

        mvc.perform(post("/api/auth/magic-link").with(from(newClient()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"busy@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("rate_limited"));
    }

    @Test
    void clientIsThrottledAfterTenLinks() throws Exception {
        String ip = newClient();

        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/auth/magic-link").with(from(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"user" + i + "@example.com\"}"))
                    .andExpect(status().isAccepted());
        }

        mvc.perform(post("/api/auth/magic-link").with(from(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user10@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("rate_limited"));
    }
}
