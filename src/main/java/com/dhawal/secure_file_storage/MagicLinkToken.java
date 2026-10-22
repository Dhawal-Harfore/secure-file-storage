package com.dhawal.secure_file_storage;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "magic_link_tokens")
public class MagicLinkToken {

    @Id
    @Column(name = "token_hash", length = 64, nullable = false, updatable = false)
    private String tokenHash;

    @Column(length = 254, nullable = false, updatable = false)
    private String email;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected MagicLinkToken() {
    }

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

    public Instant getUsedAt() {
        return usedAt;
    }
}
