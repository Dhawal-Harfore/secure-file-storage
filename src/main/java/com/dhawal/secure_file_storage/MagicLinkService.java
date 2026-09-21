package com.dhawal.secure_file_storage;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MagicLinkService {

    private final SecureRandom secureRandom = new SecureRandom();

    private final Map<String, MagicLinkToken> tokens = new ConcurrentHashMap<>();

    public String createToken(String email) {

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);

        String rawToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        String tokenHash = hash(rawToken);

        MagicLinkToken token = new MagicLinkToken(
                tokenHash,
                email,
                Instant.now().plus(15, ChronoUnit.MINUTES)
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

        if (token.isUsed()) {
            throw new IllegalStateException("Token already used");
        }

        if (Instant.now().isAfter(token.getExpiresAt())) {
            throw new IllegalStateException("Token expired");
        }

        token.markUsed();

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