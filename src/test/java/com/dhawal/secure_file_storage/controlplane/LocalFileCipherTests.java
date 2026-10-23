package com.dhawal.secure_file_storage.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileCipherTests {

    private static final String MASTER = "Xu/i0i64py0VCgQfeJpmBQ8ejQUpueP25XixX68U1z4=";

    @TempDir
    Path root;

    @Test
    void encryptsAndDecryptsAcrossSegmentsAndOffsets() throws Exception {
        LocalFileCipher cipher = new LocalFileCipher(root, MASTER);
        byte[] plain = new byte[140_000];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i * 31);
        }
        Path encrypted = root.resolve("content.bin");

        try (var out = cipher.encrypt("kh_test", Files.newOutputStream(encrypted))) {
            out.write(plain);
        }

        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        try (SeekableByteChannel channel = Files.newByteChannel(encrypted);
                var in = cipher.decrypt("kh_test", channel, 65_000)) {
            in.transferTo(restored);
        }
        assertThat(restored.toByteArray()).isEqualTo(Arrays.copyOfRange(plain, 65_000, plain.length));
    }

    @Test
    void tamperingFailsAuthentication() throws Exception {
        LocalFileCipher cipher = new LocalFileCipher(root, MASTER);
        Path encrypted = root.resolve("content.bin");
        try (var out = cipher.encrypt("kh_test", Files.newOutputStream(encrypted))) {
            out.write("secret".getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = Files.readAllBytes(encrypted);
        bytes[bytes.length - 1] ^= 1;
        Files.write(encrypted, bytes);

        assertThatThrownBy(() -> {
            try (SeekableByteChannel channel = Files.newByteChannel(encrypted);
                    var in = cipher.decrypt("kh_test", channel, 0)) {
                in.readAllBytes();
            }
        }).isInstanceOf(IOException.class)
                .hasMessageContaining("failed authentication");
    }

    @Test
    void destroyRemovesKeyAndPreventsDecryption() throws Exception {
        LocalFileCipher cipher = new LocalFileCipher(root, MASTER);
        Path encrypted = root.resolve("content.bin");
        try (var out = cipher.encrypt("kh_test", Files.newOutputStream(encrypted))) {
            out.write(1);
        }
        cipher.destroy("kh_test");

        assertThatThrownBy(() -> cipher.decrypt("kh_test",
                Files.newByteChannel(encrypted, StandardOpenOption.READ), 0))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No encryption key");
    }

    @Test
    void keysOnDiskAreWrappedWithTheMasterKey() throws Exception {
        LocalFileCipher cipher = new LocalFileCipher(root, MASTER);
        try (var out = cipher.encrypt("kh_wrapped", Files.newOutputStream(root.resolve("c.bin")))) {
            out.write(7);
        }

        try (var keys = Files.list(root.resolve(".keys"))) {
            Path key = keys.filter(k -> k.toString().endsWith(".key")).findFirst().orElseThrow();
            byte[] stored = Files.readAllBytes(key);
            assertThat(stored).hasSize(64);
            assertThat(new String(stored, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("VKW1");
        }
    }

    @Test
    void oldRawKeysAreWrappedOnStartupAndKeepWorking() throws Exception {
        Path keys = Files.createDirectories(root.resolve(".keys"));
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        String name = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("kh_legacy".getBytes(StandardCharsets.UTF_8))) + ".key";
        Files.write(keys.resolve(name), raw);

        LocalFileCipher cipher = new LocalFileCipher(root, MASTER);

        byte[] stored = Files.readAllBytes(keys.resolve(name));
        assertThat(stored).hasSize(64);
        assertThat(Arrays.equals(Arrays.copyOfRange(stored, 16, 48), raw)).isFalse();

        Path encrypted = root.resolve("legacy.bin");
        try (var out = cipher.encrypt("kh_legacy", Files.newOutputStream(encrypted))) {
            out.write("still readable".getBytes(StandardCharsets.UTF_8));
        }
        try (SeekableByteChannel channel = Files.newByteChannel(encrypted);
                var in = new LocalFileCipher(root, MASTER).decrypt("kh_legacy", channel, 0)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("still readable");
        }
    }

    @Test
    void theWrongMasterKeyCannotOpenFileKeys() throws Exception {
        Path encrypted = root.resolve("content.bin");
        try (var out = new LocalFileCipher(root, MASTER).encrypt("kh_test", Files.newOutputStream(encrypted))) {
            out.write(1);
        }
        byte[] other = new byte[32];
        new java.security.SecureRandom().nextBytes(other);
        LocalFileCipher wrong = new LocalFileCipher(root, java.util.Base64.getEncoder().encodeToString(other));

        assertThatThrownBy(() -> wrong.decrypt("kh_test", Files.newByteChannel(encrypted), 0))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Could not unwrap");
    }

    @Test
    void refusesToStartWithoutAMasterKey() {
        assertThatThrownBy(() -> new LocalFileCipher(root, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VAULT_MASTER_KEY");
    }
}
