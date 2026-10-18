package com.dhawal.secure_file_storage.controlplane;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum QualityTier {

    METADATA, LOWEST, REDUCED, ORIGINAL;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static QualityTier parse(String value) {
        if (value == null) {
            return null;
        }
        for (QualityTier tier : values()) {
            if (tier.wire().equals(value)) {
                return tier;
            }
        }
        return null;
    }

    public boolean atMost(QualityTier other) {
        return compareTo(other) <= 0;
    }

    public static QualityTier lower(QualityTier a, QualityTier b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
