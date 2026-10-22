package com.dhawal.secure_file_storage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class MagicLinkService {

    public static final Duration LIFETIME = Duration.ofMinutes(15);

    private final SecureRandom secureRandom = new SecureRandom();

    private final MagicLinkTokenRepository tokens;

    public MagicLinkService(MagicLinkTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional
    public String createToken(String email) {

        Instant now = Instant.now();

        tokens.deleteExpiredBefore(now.minus(LIFETIME));

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

        tokens.save(token);

        return rawToken;
    }

    @Transactional
    public String verifyToken(String rawToken) {

        String tokenHash = hash(rawToken);

        MagicLinkToken token = tokens.findById(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid token"));

        Instant now = Instant.now();

        if (now.isAfter(token.getExpiresAt())) {
            throw new IllegalStateException("Token expired");
        }

        if (tokens.markUsed(tokenHash, now) == 0) {
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