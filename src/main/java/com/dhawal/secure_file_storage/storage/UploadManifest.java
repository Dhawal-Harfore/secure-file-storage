package com.dhawal.secure_file_storage.storage;

import java.time.Instant;

public record UploadManifest(
        String uploadId,
        String ownerId,
        String ownerEmail,
        String name,
        String mime,
        long size,
        long chunkSize,
        Instant createdAt) {

    public int chunkCount() {
        return (int) ((size + chunkSize - 1) / chunkSize);
    }

    public long expectedLength(int index) {
        return index < chunkCount() - 1 ? chunkSize : size - chunkSize * (chunkCount() - 1);
    }
}
