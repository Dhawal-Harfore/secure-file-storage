package com.dhawal.secure_file_storage.controlplane;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "vault.storage.encryption.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(FileCipher.class)
public class LocalFileCipher implements FileCipher {

    private static final int SEGMENT_SIZE = 64 * 1024;
    private static final int NONCE_SIZE = 12;
    private static final int TAG_SIZE = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path keyRoot;

    public LocalFileCipher(@Value("${vault.storage.root}") Path storageRoot) {
        this.keyRoot = storageRoot.toAbsolutePath().normalize().resolve(".keys");
    }

    @Override
    public OutputStream encrypt(String keyHandle, OutputStream ciphertext) throws IOException {
        SecretKey key = key(keyHandle, true);
        return new SegmentEncryptingStream(keyHandle, key, ciphertext);
    }

    @Override
    public InputStream decrypt(String keyHandle, SeekableByteChannel ciphertext, long plaintextOffset)
            throws IOException {
        if (plaintextOffset < 0) {
            throw new IOException("Plaintext offset cannot be negative");
        }
        return new SegmentDecryptingStream(keyHandle, key(keyHandle, false), ciphertext, plaintextOffset);
    }

    @Override
    public void destroy(String keyHandle) throws IOException {
        Files.deleteIfExists(keyPath(keyHandle));
    }

    private SecretKey key(String keyHandle, boolean create) throws IOException {
        Path path = keyPath(keyHandle);
        if (create) {
            Files.createDirectories(keyRoot);
            if (Files.exists(path)) {
                return readKey(path);
            }
            SecretKey generated;
            try {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                generated = generator.generateKey();
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not create encryption key", e);
            }
            Path temporary = Files.createTempFile(keyRoot, "key-", ".tmp");
            try {
                Files.write(temporary, generated.getEncoded());
                try {
                    Files.move(temporary, path);
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    return readKey(path);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return generated;
        }
        if (!Files.exists(path)) {
            throw new IOException("No encryption key for " + keyHandle);
        }
        return readKey(path);
    }

    private SecretKey readKey(Path path) throws IOException {
        byte[] encoded = Files.readAllBytes(path);
        if (encoded.length != 32) {
            throw new IOException("Invalid encryption key");
        }
        return new SecretKeySpec(encoded, "AES");
    }

    private Path keyPath(String keyHandle) {
        if (keyHandle == null || keyHandle.isBlank()) {
            throw new IllegalArgumentException("Key handle is required");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(keyHandle.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return keyRoot.resolve(java.util.HexFormat.of().formatHex(hash) + ".key");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] nonce() {
        byte[] nonce = new byte[NONCE_SIZE];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] nonce, String handle, long segment)
            throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_SIZE * 8, nonce));
            cipher.updateAAD((handle + ":" + segment).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not initialize encryption", e);
        }
    }

    private static final class SegmentEncryptingStream extends OutputStream {
        private final String handle;
        private final SecretKey key;
        private final OutputStream out;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(SEGMENT_SIZE);
        private long segment;
        private boolean closed;

        private SegmentEncryptingStream(String handle, SecretKey key, OutputStream out) {
            this.handle = handle;
            this.key = key;
            this.out = out;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value});
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
            while (length > 0) {
                int count = Math.min(length, SEGMENT_SIZE - buffer.size());
                buffer.write(bytes, offset, count);
                offset += count;
                length -= count;
                if (buffer.size() == SEGMENT_SIZE) {
                    flushSegment();
                }
            }
        }

        private void flushSegment() throws IOException {
            byte[] plain = buffer.toByteArray();
            byte[] nonce = nonce();
            out.write(nonce);
            try {
                out.write(cipher(Cipher.ENCRYPT_MODE, key, nonce, handle, segment++)
                        .doFinal(plain));
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not encrypt segment", e);
            }
            buffer.reset();
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                if (buffer.size() > 0) {
                    flushSegment();
                }
                closed = true;
                out.close();
            }
        }
    }

    private static final class SegmentDecryptingStream extends InputStream {
        private final String handle;
        private final SecretKey key;
        private final SeekableByteChannel channel;
        private long segment;
        private byte[] current = new byte[0];
        private int position;
        private boolean eof;

        private SegmentDecryptingStream(String handle, SecretKey key,
                SeekableByteChannel channel, long offset) throws IOException {
            this.handle = handle;
            this.key = key;
            this.channel = channel;
            this.segment = offset / SEGMENT_SIZE;
            load(offset % SEGMENT_SIZE);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (eof) {
                return -1;
            }
            int total = 0;
            while (length > 0 && !eof) {
                if (position == current.length) {
                    load(0);
                    if (eof) {
                        break;
                    }
                }
                int count = Math.min(length, current.length - position);
                System.arraycopy(current, position, bytes, offset, count);
                position += count;
                offset += count;
                length -= count;
                total += count;
            }
            return total == 0 ? -1 : total;
        }

        private void load(long skip) throws IOException {
            long start = segment * (SEGMENT_SIZE + NONCE_SIZE + TAG_SIZE);
            if (start >= channel.size()) {
                eof = true;
                return;
            }
            channel.position(start);
            byte[] nonce = readExactly(NONCE_SIZE);
            long encryptedLength = Math.min(SEGMENT_SIZE + TAG_SIZE,
                    channel.size() - start - NONCE_SIZE);
            byte[] encrypted = readExactly((int) encryptedLength);
            try {
                current = cipher(Cipher.DECRYPT_MODE, key, nonce, handle, segment++)
                        .doFinal(encrypted);
            } catch (GeneralSecurityException e) {
                throw new IOException("Stored content failed authentication", e);
            }
            position = (int) skip;
            if (position > current.length) {
                throw new IOException("Plaintext offset is outside stored content");
            }
        }

        private byte[] readExactly(int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("Stored content ended unexpectedly");
                }
            }
            return buffer.array();
        }
    }
}
