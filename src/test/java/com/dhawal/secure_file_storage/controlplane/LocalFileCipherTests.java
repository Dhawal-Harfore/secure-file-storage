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

    @TempDir
    Path root;

    @Test
    void encryptsAndDecryptsAcrossSegmentsAndOffsets() throws Exception {
        LocalFileCipher cipher = new LocalFileCipher(root);
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
        LocalFileCipher cipher = new LocalFileCipher(root);
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
        LocalFileCipher cipher = new LocalFileCipher(root);
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
}
