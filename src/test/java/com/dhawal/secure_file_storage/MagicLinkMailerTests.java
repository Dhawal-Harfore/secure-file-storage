package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class MagicLinkMailerTests {

    private record Captured(String authorization, String body) {
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final BlockingQueue<Captured> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startFakeResend() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Captured(exchange.getRequestHeaders().getFirst("Authorization"), body));
            byte[] reply = "{\"id\":\"e_1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/emails";
    }

    @AfterEach
    void stopFakeResend() {
        server.stop(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsTheLinkThroughResend() throws Exception {
        MagicLinkMailer mailer = new MagicLinkMailer(json, "re_test", endpoint,
                "Vault <signin@thecollege.cloud>", "https://thecollege.cloud/");

        mailer.send("asha@example.com", "abc-DEF_123");

        Captured request = requests.poll(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.authorization()).isEqualTo("Bearer re_test");

        Map<String, Object> payload = json.readValue(request.body(), Map.class);
        String link = "https://thecollege.cloud/unlock.html?token=abc-DEF_123";

        assertThat(payload.get("from")).isEqualTo("Vault <signin@thecollege.cloud>");
        assertThat(payload.get("to")).isEqualTo(List.of("asha@example.com"));
        assertThat((String) payload.get("html")).contains("href=\"" + link + "\"").doesNotContain("{{");
        assertThat((String) payload.get("text")).contains(link).contains("15 minutes");
    }

    @Test
    void withoutKeyNothingIsSent() throws Exception {
        MagicLinkMailer mailer = new MagicLinkMailer(json, "  ", endpoint,
                "Vault <signin@thecollege.cloud>", "https://thecollege.cloud");

        assertThatThrownBy(() -> mailer.send("asha@example.com", "abc"))
                .isInstanceOf(MagicLinkMailer.MailDeliveryException.class)
                .hasMessage("Email provider is not configured");

        assertThat(requests.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void rejectsNonSuccessfulResendResponse() throws Exception {
        server.removeContext("/emails");
        server.createContext("/emails", exchange -> {
            byte[] reply = "{\"message\":\"The from address is not verified\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });

        MagicLinkMailer mailer = new MagicLinkMailer(json, "re_test", endpoint,
                "Vault <signin@thecollege.cloud>", "https://thecollege.cloud");

        assertThatThrownBy(() -> mailer.send("asha@example.com", "abc"))
                .isInstanceOf(MagicLinkMailer.MailDeliveryException.class)
                .hasMessage("Email provider refused the request");
    }
}
