package com.dhawal.secure_file_storage.persistence;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "file_renditions")
@IdClass(FileRendition.Key.class)
public class FileRendition {

    public static class Key implements Serializable {

        private String fileId;
        private QualityTier tier;

        protected Key() {
        }

        public Key(String fileId, QualityTier tier) {
            this.fileId = fileId;
            this.tier = tier;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(fileId, key.fileId) && tier == key.tier;
        }

        @Override
        public int hashCode() {
            return Objects.hash(fileId, tier);
        }
    }

    @Id
    @Column(name = "file_id", length = 40, nullable = false, updatable = false)
    private String fileId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false, updatable = false)
    private QualityTier tier;

    @Column(length = 255, nullable = false)
    private String mime;

    @Column(nullable = false)
    private long size;

    @Column(name = "key_handle", length = 80, nullable = false)
    private String keyHandle;

    protected FileRendition() {
    }

    public FileRendition(String fileId, QualityTier tier, String mime, long size, String keyHandle) {
        this.fileId = fileId;
        this.tier = tier;
        this.mime = mime;
        this.size = size;
        this.keyHandle = keyHandle;
    }

    public String getFileId() {
        return fileId;
    }

    public QualityTier getTier() {
        return tier;
    }

    public String getMime() {
        return mime;
    }

    public long getSize() {
        return size;
    }

    public String getKeyHandle() {
        return keyHandle;
    }
}
