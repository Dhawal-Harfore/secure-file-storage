package com.dhawal.secure_file_storage.controlplane;

import java.time.Instant;
import java.util.List;

public record Permit(
        String fileId,
        String subjectId,
        String accessLevel,
        List<String> actions,
        QualityTier qualityTier,
        String keyHandle,
        Instant expiresAt,
        Integer maxViews,
        Integer viewsUsed,
        String reason) {

    public boolean allows(String action) {
        return actions != null && actions.contains(action);
    }

    public boolean servesBytes() {
        return qualityTier != null
                && qualityTier != QualityTier.METADATA
                && keyHandle != null
                && (allows("view") || allows("download"));
    }
}
