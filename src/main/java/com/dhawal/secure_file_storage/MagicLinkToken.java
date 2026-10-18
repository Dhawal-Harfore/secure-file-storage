package com.dhawal.secure_file_storage;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

public class MagicLinkToken {

    private final String tokenHash;
    private final String email;
    private final Instant expiresAt;
    private final AtomicBoolean used = new AtomicBoolean(false);

    public MagicLinkToken(String tokenHash, String email, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.email = email;
        this.expiresAt = expiresAt;
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
        return used.get();
    }

    public boolean markUsed() {
        return used.compareAndSet(false, true);
    }
}