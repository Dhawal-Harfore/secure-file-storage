package com.dhawal.secure_file_storage;

import java.time.Instant;

public class MagicLinkToken {

    private final String tokenHash;
    private final String email;
    private final Instant expiresAt;
    private boolean used;

    public MagicLinkToken(String tokenHash, String email, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.email = email;
        this.expiresAt = expiresAt;
        this.used = false;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getEmail() {
        return email;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isUsed() {
        return used;
    }

    public void markUsed() {
        this.used = true;
    }
}