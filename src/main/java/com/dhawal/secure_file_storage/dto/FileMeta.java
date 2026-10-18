package com.dhawal.secure_file_storage.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.dhawal.secure_file_storage.controlplane.FileKind;
import com.dhawal.secure_file_storage.controlplane.Permit;
import com.dhawal.secure_file_storage.controlplane.QualityTier;
import com.dhawal.secure_file_storage.controlplane.StoredFile;

public record FileMeta(
        String id,
        String name,
        String mime,
        long size,
        FileKind kind,
        String ownerId,
        String ownerEmail,
        Instant createdAt,
        Instant updatedAt,
        List<QualityTier> renditions,
        Permit permit) {

    public static FileMeta of(StoredFile file, Permit permit) {
        return new FileMeta(file.id(), file.name(), file.mime(), file.size(), file.kind(),
                file.ownerId(), file.ownerEmail(), file.createdAt(), file.updatedAt(),
                file.renditions().keySet().stream().sorted(Comparator.reverseOrder()).toList(),
                permit);
    }
}
