package com.dhawal.secure_file_storage.controlplane;

public record Rendition(QualityTier tier, String mime, long size, String keyHandle) {
}
