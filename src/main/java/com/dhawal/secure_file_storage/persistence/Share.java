package com.dhawal.secure_file_storage.persistence;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "shares",
        uniqueConstraints = @UniqueConstraint(name = "uk_share_token_hash",
                columnNames = "token_hash"))
public class Share {

    @Id
    @Column(length = 40, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private StoredFile file;

    @Column(name = "token_hash", length = 64, nullable = false, updatable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", length = 16, nullable = false)
    private AccessLevel accessLevel;

    @Convert(converter = ActionSetConverter.class)
    @Column(length = 128, nullable = false)
    private Set<Action> actions = EnumSet.noneOf(Action.class);

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "max_views")
    private Integer maxViews;

    @Column(name = "views_used", nullable = false)
    private int viewsUsed;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private UserAccount createdBy;

    protected Share() {
    }

    public Share(String id, StoredFile file, String tokenHash,
            AccessLevel accessLevel, Set<Action> actions, Instant expiresAt,
            Integer maxViews, UserAccount createdBy) {
        this.id = id;
        this.file = file;
        this.tokenHash = tokenHash;
        this.accessLevel = accessLevel;
        this.actions = actions.isEmpty()
                ? EnumSet.noneOf(Action.class)
                : EnumSet.copyOf(actions);
        this.expiresAt = expiresAt;
        this.maxViews = maxViews;
        this.createdBy = createdBy;
    }

    @jakarta.persistence.PrePersist
    void setCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() {
        return id;
    }

    public StoredFile getFile() {
        return file;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public Set<Action> getActions() {
        return Set.copyOf(actions);
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Integer getMaxViews() {
        return maxViews;
    }

    public int getViewsUsed() {
        return viewsUsed;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UserAccount getCreatedBy() {
        return createdBy;
    }
}
