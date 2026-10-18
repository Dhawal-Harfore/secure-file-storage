package com.dhawal.secure_file_storage.controlplane;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

public enum FileKind {

    IMAGE, VIDEO, PDF, OTHER;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FileKind of(String mime) {
        if (mime == null) {
            return OTHER;
        }
        if (mime.equals("application/pdf")) {
            return PDF;
        }
        if (mime.startsWith("video/")) {
            return VIDEO;
        }
        if (mime.startsWith("image/") && !mime.startsWith("image/svg")) {
            return IMAGE;
        }
        return OTHER;
    }
}
