package com.dhawal.secure_file_storage.dto;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record SessionUser(String id, String email) implements Serializable {

    public static SessionUser of(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(email.getBytes(StandardCharsets.UTF_8));

            return new SessionUser(
                    "u_" + HexFormat.of().formatHex(digest, 0, 4),
                    email
            );

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
