package com.dhawal.secure_file_storage;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MagicLinkService {

    public static final Duration LIFETIME = Duration.ofMinutes(15);

    private final SecureRandom secureRandom = new SecureRandom();

    private final Map<String, MagicLinkToken> tokens = new ConcurrentHashMap<>();

    public String createToken(String email) {

        Instant now = Instant.now();

        tokens.values().removeIf(t -> now.isAfter(t.getExpiresAt().plus(LIFETIME)));

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);

        String rawToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        String tokenHash = hash(rawToken);

        MagicLinkToken token = new MagicLinkToken(
                tokenHash,
                email,
                now.plus(LIFETIME)
        );

        tokens.put(tokenHash, token);

        return rawToken;
    }

    public String verifyToken(String rawToken) {

        String tokenHash = hash(rawToken);

        MagicLinkToken token = tokens.get(tokenHash);

        if (token == null) {
            throw new IllegalArgumentException("Invalid token");
        }

        if (Instant.now().isAfter(token.getExpiresAt())) {
            throw new IllegalStateException("Token expired");
        }

        if (!token.markUsed()) {
            throw new IllegalStateException("Token already used");
        }

        return token.getEmail();
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return Base64.getEncoder().encodeToString(hash);

        } catch (Exception e) {
            throw new IllegalStateException("Could not hash token", e);
        }
    }
}