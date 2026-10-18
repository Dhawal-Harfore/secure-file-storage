package com.dhawal.secure_file_storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.dhawal.secure_file_storage.controlplane.AuditEvent;
import com.dhawal.secure_file_storage.controlplane.AuditLog;
import com.dhawal.secure_file_storage.controlplane.FileCatalog;
import com.dhawal.secure_file_storage.controlplane.FileCipher;
import com.dhawal.secure_file_storage.controlplane.Permit;
import com.dhawal.secure_file_storage.controlplane.PermitService;
import com.dhawal.secure_file_storage.controlplane.QualityTier;
import com.dhawal.secure_file_storage.controlplane.Rendition;
import com.dhawal.secure_file_storage.controlplane.StoredFile;
import com.dhawal.secure_file_storage.storage.Ids;

@TestConfiguration
public class ControlPlaneDoubles {

    @Bean
    TestCipher testCipher() {
        return new TestCipher();
    }

    @Bean
    TestCatalog testCatalog() {
        return new TestCatalog();
    }

    @Bean
    TestPermits testPermits(TestCatalog catalog) {
        return new TestPermits(catalog);
    }

    @Bean
    TestAudit testAudit() {
        return new TestAudit();
    }

    public static class TestCipher implements FileCipher {

        private static final SecureRandom RANDOM = new SecureRandom();

        public final Map<String, SecretKey> keys = new ConcurrentHashMap<>();
        public final Set<String> destroyed = ConcurrentHashMap.newKeySet();

        @Override
        public OutputStream encrypt(String keyHandle, OutputStream ciphertext) throws IOException {
            SecretKey key = keys.computeIfAbsent(keyHandle, h -> newKey());
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Arrays.fill(iv, 8, 16, (byte) 0);
            ciphertext.write(iv);
            return new CipherOutputStream(ciphertext, cipher(Cipher.ENCRYPT_MODE, key, iv));
        }

        @Override
        public InputStream decrypt(String keyHandle, SeekableByteChannel ciphertext, long plaintextOffset)
                throws IOException {
            SecretKey key = keys.get(keyHandle);
            if (key == null) {
                throw new IOException("No key for " + keyHandle);
            }
            ByteBuffer iv = ByteBuffer.allocate(16);
            ciphertext.position(0);
            while (iv.hasRemaining() && ciphertext.read(iv) > 0) {
            }
            long block = plaintextOffset / 16;
            byte[] counter = BigInteger.ONE.shiftLeft(128)
                    .add(new BigInteger(1, iv.array()).add(BigInteger.valueOf(block)))
                    .toByteArray();
            counter = Arrays.copyOfRange(counter, counter.length - 16, counter.length);
            ciphertext.position(16 + block * 16);
            InputStream in = new CipherInputStream(Channels.newInputStream(ciphertext),
                    cipher(Cipher.DECRYPT_MODE, key, counter));
            in.skipNBytes(plaintextOffset % 16);
            return in;
        }

        @Override
        public void destroy(String keyHandle) {
            keys.remove(keyHandle);
            destroyed.add(keyHandle);
        }

        private static SecretKey newKey() {
            try {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                return generator.generateKey();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        private static Cipher cipher(int mode, SecretKey key, byte[] iv) {
            try {
                Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
                cipher.init(mode, key, new IvParameterSpec(iv));
                return cipher;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static class TestCatalog implements FileCatalog {

        public final Map<String, StoredFile> files = new ConcurrentHashMap<>();

        @Override
        public void add(StoredFile file) {
            files.put(file.id(), file);
        }

        @Override
        public Optional<StoredFile> find(String fileId) {
            return Optional.ofNullable(files.get(fileId));
        }

        @Override
        public void addRendition(String fileId, Rendition rendition) {
            files.computeIfPresent(fileId, (id, file) -> {
                Map<QualityTier, Rendition> renditions = new HashMap<>(file.renditions());
                renditions.put(rendition.tier(), rendition);
                return new StoredFile(file.id(), file.name(), file.mime(), file.size(), file.kind(),
                        file.ownerId(), file.ownerEmail(), file.createdAt(), file.updatedAt(), Map.copyOf(renditions));
            });
        }
    }

    public static class TestPermits implements PermitService {

        private final TestCatalog catalog;
        private final Map<String, Permit> grants = new ConcurrentHashMap<>();

        TestPermits(TestCatalog catalog) {
            this.catalog = catalog;
        }

        public void grant(String fileId, String subjectId, String level, QualityTier tier, String... actions) {
            grants.put(fileId + "|" + subjectId, new Permit(fileId, subjectId, level, List.of(actions), tier,
                    tier == QualityTier.METADATA ? null : Ids.keyHandle(fileId, tier),
                    null, null, null, "policy:" + level));
        }

        @Override
        public Permit evaluate(String fileId, String subjectId) {
            Permit granted = grants.get(fileId + "|" + subjectId);
            if (granted != null) {
                return granted;
            }
            StoredFile file = catalog.files.get(fileId);
            if (file != null && file.ownerId().equals(subjectId)) {
                return new Permit(fileId, subjectId, "owner",
                        List.of("view", "download", "edit", "share", "revoke", "delete"), QualityTier.ORIGINAL,
                        Ids.keyHandle(fileId, QualityTier.ORIGINAL), null, null, null, "policy:owner");
            }
            return new Permit(fileId, subjectId, null, List.of(), QualityTier.METADATA, null, null, null, null,
                    "policy:none");
        }
    }

    public static class TestAudit implements AuditLog {

        public final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }

        public List<AuditEvent> forFile(String fileId) {
            return events.stream().filter(e -> fileId.equals(e.fileId())).toList();
        }
    }
}
