package com.dhawal.secure_file_storage.controlplane;

import java.time.Instant;

public record AuditEvent(
        String id,
        Instant at,
        String actorId,
        String actorEmail,
        String fileId,
        String fileName,
        String action,
        String result,
        QualityTier qualityTier,
        String shareId,
        String ip,
        String userAgent) {
}
