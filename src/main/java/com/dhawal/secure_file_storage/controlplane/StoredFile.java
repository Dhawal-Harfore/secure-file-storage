package com.dhawal.secure_file_storage.controlplane;

import java.time.Instant;
import java.util.Map;

public record StoredFile(
        String id,
        String name,
        String mime,
        long size,
        FileKind kind,
        String ownerId,
        String ownerEmail,
        Instant createdAt,
        Instant updatedAt,
        Map<QualityTier, Rendition> renditions) {

    public Rendition bestAtMost(QualityTier ceiling) {
        for (QualityTier tier = ceiling; tier != QualityTier.METADATA;
                tier = QualityTier.values()[tier.ordinal() - 1]) {
            Rendition rendition = renditions.get(tier);
            if (rendition != null) {
                return rendition;
            }
        }
        return null;
    }
}
