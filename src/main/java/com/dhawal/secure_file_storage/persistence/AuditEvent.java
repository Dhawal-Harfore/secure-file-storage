package com.dhawal.secure_file_storage.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @Column(length = 40, nullable = false, updatable = false)
    private String id;

    @Column(name = "at_time", nullable = false, updatable = false)
    private Instant at;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private UserAccount actor;

    @Column(name = "actor_email", length = 254)
    private String actorEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id")
    private StoredFile file;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(length = 32, nullable = false)
    private String action;

    @Column(length = 32, nullable = false)
    private String result;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_tier", length = 16)
    private QualityTier qualityTier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "share_id")
    private Share share;

    @Column(length = 45)
    private String ip;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    protected AuditEvent() {
    }

    public AuditEvent(String id, UserAccount actor, String actorEmail,
            StoredFile file, String fileName, String action, String result,
            QualityTier qualityTier, Share share, String ip, String userAgent) {
        this.id = id;
        this.actor = actor;
        this.actorEmail = actorEmail;
        this.file = file;
        this.fileName = fileName;
        this.action = action;
        this.result = result;
        this.qualityTier = qualityTier;
        this.share = share;
        this.ip = ip;
        this.userAgent = userAgent;
    }

    @PrePersist
    void setAt() {
        if (at == null) {
            at = Instant.now();
        }
    }

    public String getId() {
        return id;
    }

    public Instant getAt() {
        return at;
    }

    public UserAccount getActor() {
        return actor;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public StoredFile getFile() {
        return file;
    }

    public String getFileName() {
        return fileName;
    }

    public String getAction() {
        return action;
    }

    public String getResult() {
        return result;
    }

    public QualityTier getQualityTier() {
        return qualityTier;
    }

    public Share getShare() {
        return share;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }
}
