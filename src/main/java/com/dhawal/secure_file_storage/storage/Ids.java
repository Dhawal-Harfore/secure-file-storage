package com.dhawal.secure_file_storage.storage;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

import com.dhawal.secure_file_storage.controlplane.QualityTier;

public final class Ids {

    public static final Pattern UPLOAD = Pattern.compile("up_[a-z2-7]{20}");
    public static final Pattern FILE = Pattern.compile("f_[0-9a-f]{16}");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private Ids() {
    }

    public static String newUploadId() {
        StringBuilder id = new StringBuilder("up_");
        for (int i = 0; i < 20; i++) {
            id.append(BASE32[RANDOM.nextInt(BASE32.length)]);
        }
        return id.toString();
    }

    public static String newFileId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "f_" + HexFormat.of().formatHex(bytes);
    }

    public static String newEventId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "a_" + HexFormat.of().formatHex(bytes);
    }

    public static String keyHandle(String fileId, QualityTier tier) {
        return "kh_" + fileId.substring(2) + "_" + tier.wire();
    }

    public static String stagingKeyHandle(String uploadId) {
        return "kh_" + uploadId.substring(3) + "_staging";
    }
}
