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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "stored_files")
public class StoredFile {

    @Id
    @Column(length = 40, nullable = false, updatable = false)
    private String id;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(length = 255, nullable = false)
    private String mime;

    @Column(nullable = false)
    private long size;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private FileKind kind;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserAccount owner;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected StoredFile() {
    }

    public StoredFile(String id, String name, String mime, long size,
            FileKind kind, UserAccount owner) {
        this.id = id;
        this.name = name;
        this.mime = mime;
        this.size = size;
        this.kind = kind;
        this.owner = owner;
    }

    @PrePersist
    void setCreatedAt() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void setUpdatedAt() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getMime() {
        return mime;
    }

    public long getSize() {
        return size;
    }

    public FileKind getKind() {
        return kind;
    }

    public UserAccount getOwner() {
        return owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void markDeleted(Instant at) {
        if (deletedAt == null) {
            deletedAt = at;
        }
    }
}
