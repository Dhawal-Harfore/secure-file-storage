package com.dhawal.secure_file_storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

@Service
public class MagicLinkMailer {

    private static final Logger log = LoggerFactory.getLogger(MagicLinkMailer.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final JsonMapper json;
    private final String apiKey;
    private final URI endpoint;
    private final String from;
    private final String publicUrl;
    private final String template;

    public MagicLinkMailer(
            JsonMapper json,
            @Value("${vault.mail.resend-key}") String apiKey,
            @Value("${vault.mail.resend-url}") String endpoint,
            @Value("${vault.mail.from}") String from,
            @Value("${vault.public-url}") String publicUrl) {

        this.json = json;
        this.apiKey = apiKey.strip();
        this.endpoint = URI.create(endpoint);
        this.from = from;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.template = load("mail/magic-link.html");
    }

    public void send(String email, String token) {

        String link = publicUrl + "/unlock.html?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);

        if (apiKey.isEmpty()) {
            log.warn("RESEND_API_KEY is not set, so no email was sent. Sign-in link for {}: {}", email, link);
            return;
        }

        String minutes = String.valueOf(MagicLinkService.LIFETIME.toMinutes());

        String body = json.writeValueAsString(Map.of(
                "from", from,
                "to", List.of(email),
                "subject", "Your Vault sign-in link",
                "html", template.replace("{{link}}", link).replace("{{minutes}}", minutes),
                "text", "Sign in to Vault: " + link + "\n\nThe link works once and expires in "
                        + minutes + " minutes. If you didn't ask for it, ignore this email.\n"
        ));

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        log.error("Could not reach Resend to send a sign-in email", error);
                    } else if (response.statusCode() >= 300) {
                        log.error("Resend refused a sign-in email: {} {}", response.statusCode(), response.body());
                    }
                });
    }

    private static String load(String path) {
        try (InputStream in = MagicLinkMailer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
