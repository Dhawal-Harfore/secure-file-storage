package com.dhawal.secure_file_storage.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import com.dhawal.secure_file_storage.controlplane.AuditEvent;
import com.dhawal.secure_file_storage.controlplane.FileCipher;
import com.dhawal.secure_file_storage.controlplane.FileKind;
import com.dhawal.secure_file_storage.controlplane.QualityTier;
import com.dhawal.secure_file_storage.controlplane.Rendition;
import com.dhawal.secure_file_storage.controlplane.StoredFile;
import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.rendition.RenditionJobs;

import tools.jackson.databind.json.JsonMapper;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private static final Pattern MIME =
            Pattern.compile("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public record Status(String uploadId, long chunkSize, List<Integer> received) {
    }

    private final FileStore store;
    private final ControlPlane controlPlane;
    private final RenditionJobs renditions;
    private final JsonMapper json;
    private final long chunkSize;
    private final long maxUploadSize;
    private final int maxActiveUploads;
    private final Duration uploadTtl;
    private final long freeSpaceReserve;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public UploadService(
            FileStore store,
            ControlPlane controlPlane,
            RenditionJobs renditions,
            JsonMapper json,
            @Value("${vault.storage.chunk-size}") DataSize chunkSize,
            @Value("${vault.storage.max-upload-size}") DataSize maxUploadSize,
            @Value("${vault.storage.max-active-uploads}") int maxActiveUploads,
            @Value("${vault.storage.upload-ttl}") Duration uploadTtl,
            @Value("${vault.storage.free-space-reserve}") DataSize freeSpaceReserve) {

        this.store = store;
        this.controlPlane = controlPlane;
        this.renditions = renditions;
        this.json = json;
        this.chunkSize = chunkSize.toBytes();
        this.maxUploadSize = maxUploadSize.toBytes();
        this.maxActiveUploads = maxActiveUploads;
        this.uploadTtl = uploadTtl;
        this.freeSpaceReserve = freeSpaceReserve.toBytes();
    }

    public Status initiate(SessionUser owner, String name, String mime, Long size) throws IOException {

        controlPlane.cipher();
        purgeExpired();

        String cleanName = cleanName(name);
        String cleanMime = cleanMime(mime);

        if (size == null || size <= 0) {
            throw ApiException.badRequest("invalid_upload", "The file is empty.");
        }
        if (size > maxUploadSize) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large",
                    "Files can be up to " + DataSize.ofBytes(maxUploadSize).toGigabytes() + " GB.");
        }
        if (store.usableSpace() - freeSpaceReserve < size * 3) {
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "insufficient_storage",
                    "The server is out of space for a file this size. Try again later.");
        }
        if (activeUploads(owner.id()) >= maxActiveUploads) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Finish or cancel one of your other uploads first.");
        }

        String uploadId = Ids.newUploadId();
        UploadManifest manifest = new UploadManifest(uploadId, owner.id(), owner.email(),
                cleanName, cleanMime, size, chunkSize, Instant.now());

        Files.createDirectories(store.staging(uploadId));
        Path tmp = store.staging(uploadId).resolve("manifest.json.tmp");
        Files.writeString(tmp, json.writeValueAsString(manifest));
        FileStore.moveIntoPlace(tmp, store.manifest(uploadId));

        return new Status(uploadId, chunkSize, List.of());
    }

    public Status status(SessionUser owner, String uploadId) throws IOException {
        UploadManifest manifest = manifest(owner, uploadId);
        return new Status(uploadId, manifest.chunkSize(), received(manifest));
    }

    public List<Integer> putChunk(SessionUser owner, String uploadId, int index, InputStream body)
            throws IOException {

        FileCipher cipher = controlPlane.cipher();
        UploadManifest manifest = manifest(owner, uploadId);

        if (index < 0 || index >= manifest.chunkCount()) {
            throw ApiException.badRequest("invalid_chunk",
                    "Chunk " + index + " is outside this upload.");
        }

        long expected = manifest.expectedLength(index);
        Path tmp = Files.createTempFile(store.staging(uploadId), index + "-", ".tmp");

        try {
            long written;
            try (OutputStream out = cipher.encrypt(Ids.stagingKeyHandle(uploadId), Files.newOutputStream(tmp))) {
                written = copy(body, out, expected + 1);
            }
            if (written != expected) {
                throw ApiException.badRequest("invalid_chunk", "Chunk " + index + " should be "
                        + expected + " bytes but was " + (written > expected ? "longer" : written + " bytes") + ".");
            }

            ReentrantLock lock = lock(uploadId);
            lock.lock();
            try {
                if (!Files.exists(store.manifest(uploadId))) {
                    throw ApiException.notFound("That upload no longer exists.");
                }
                FileStore.moveIntoPlace(tmp, store.chunk(uploadId, index));
                Files.setLastModifiedTime(store.manifest(uploadId), FileTime.from(Instant.now()));
                return received(manifest);
            } finally {
                lock.unlock();
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public StoredFile complete(SessionUser owner, String uploadId, String sha256, RequestInfo request)
            throws IOException {

        FileCipher cipher = controlPlane.cipher();
        controlPlane.catalog();
        controlPlane.audit();

        String expectedSha = sha256 == null ? "" : sha256.strip().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(expectedSha).matches()) {
            throw ApiException.badRequest("invalid_upload", "Send the file's SHA-256 as 64 hex characters.");
        }

        ReentrantLock lock = lock(uploadId);
        lock.lock();
        try {
            UploadManifest manifest = manifest(owner, uploadId);

            List<Integer> received = received(manifest);
            if (received.size() != manifest.chunkCount()) {
                throw ApiException.badRequest("incomplete_upload", "Upload "
                        + (manifest.chunkCount() - received.size()) + " more chunk(s) before completing.");
            }

            String fileId = Ids.newFileId();
            String originalKey = Ids.keyHandle(fileId, QualityTier.ORIGINAL);
            Path original = store.blob(fileId, QualityTier.ORIGINAL);
            Path tmp = original.resolveSibling("original.bin.tmp");
            MessageDigest digest = sha256();

            try (OutputStream out = cipher.encrypt(originalKey, Files.newOutputStream(tmp))) {
                for (int i = 0; i < manifest.chunkCount(); i++) {
                    try (SeekableByteChannel channel = Files.newByteChannel(store.chunk(uploadId, i));
                            InputStream plain = cipher.decrypt(Ids.stagingKeyHandle(uploadId), channel, 0)) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = plain.read(buffer)) > 0) {
                            digest.update(buffer, 0, n);
                            out.write(buffer, 0, n);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(tmp);
                FileStore.deleteTree(store.fileDir(fileId));
                cipher.destroy(originalKey);
                throw e;
            }

            if (!HexFormat.of().formatHex(digest.digest()).equals(expectedSha)) {
                Files.deleteIfExists(tmp);
                FileStore.deleteTree(store.fileDir(fileId));
                cipher.destroy(originalKey);
                discard(uploadId);
                throw ApiException.badRequest("checksum_mismatch",
                        "The uploaded bytes don't match the file's checksum. Upload it again.");
            }

            FileStore.moveIntoPlace(tmp, original);

            Instant now = Instant.now();
            StoredFile file = new StoredFile(fileId, manifest.name(), manifest.mime(), manifest.size(),
                    FileKind.of(manifest.mime()), manifest.ownerId(), manifest.ownerEmail(), now, now,
                    Map.of(QualityTier.ORIGINAL, new Rendition(QualityTier.ORIGINAL, manifest.mime(),
                            manifest.size(), originalKey)));

            controlPlane.catalog().add(file);
            discard(uploadId);

            controlPlane.audit().record(new AuditEvent(Ids.newEventId(), now, owner.id(), owner.email(),
                    fileId, file.name(), "upload", "permit", QualityTier.ORIGINAL, null,
                    request.ip(), request.userAgent()));

            if (file.kind() != FileKind.OTHER) {
                renditions.submit(fileId);
            }

            return file;
        } finally {
            lock.unlock();
            locks.remove(uploadId);
        }
    }

    public void abort(SessionUser owner, String uploadId) throws IOException {
        controlPlane.cipher();
        ReentrantLock lock = lock(uploadId);
        lock.lock();
        try {
            manifest(owner, uploadId);
            discard(uploadId);
        } finally {
            lock.unlock();
            locks.remove(uploadId);
        }
    }

    private void discard(String uploadId) {
        FileStore.deleteTree(store.staging(uploadId));
        try {
            controlPlane.cipher().destroy(Ids.stagingKeyHandle(uploadId));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not destroy the staging key for {}", uploadId, e);
        }
    }

    private UploadManifest manifest(SessionUser owner, String uploadId) throws IOException {
        if (uploadId == null || !Ids.UPLOAD.matcher(uploadId).matches()) {
            throw ApiException.notFound("That upload no longer exists.");
        }
        Path path = store.manifest(uploadId);
        if (!Files.exists(path)) {
            throw ApiException.notFound("That upload no longer exists.");
        }
        UploadManifest manifest = json.readValue(Files.readString(path), UploadManifest.class);
        if (!manifest.ownerId().equals(owner.id())) {
            throw ApiException.notFound("That upload no longer exists.");
        }
        return manifest;
    }

    private List<Integer> received(UploadManifest manifest) {
        List<Integer> received = new ArrayList<>();
        for (int i = 0; i < manifest.chunkCount(); i++) {
            if (Files.exists(store.chunk(manifest.uploadId(), i))) {
                received.add(i);
            }
        }
        return received;
    }

    private int activeUploads(String ownerId) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(store.stagingRoot())) {
            for (Path dir : dirs) {
                Path manifest = dir.resolve("manifest.json");
                if (Files.exists(manifest)
                        && json.readValue(Files.readString(manifest), UploadManifest.class).ownerId().equals(ownerId)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void purgeExpired() throws IOException {
        Instant cutoff = Instant.now().minus(uploadTtl);
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(store.stagingRoot())) {
            for (Path dir : dirs) {
                Path manifest = dir.resolve("manifest.json");
                Path touched = Files.exists(manifest) ? manifest : dir;
                if (Files.getLastModifiedTime(touched).toInstant().isBefore(cutoff)) {
                    String uploadId = dir.getFileName().toString();
                    if (Ids.UPLOAD.matcher(uploadId).matches()) {
                        discard(uploadId);
                    }
                }
            }
        }
    }

    private ReentrantLock lock(String uploadId) {
        return locks.computeIfAbsent(uploadId, id -> new ReentrantLock());
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while (total < limit && (n = in.read(buffer, 0, (int) Math.min(buffer.length, limit - total))) > 0) {
            out.write(buffer, 0, n);
            total += n;
        }
        return total;
    }

    static String cleanName(String name) {
        String base = name == null ? "" : name;
        base = base.substring(Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\')) + 1);
        base = base.replaceAll("\\p{Cntrl}", "").strip();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw ApiException.badRequest("invalid_upload", "The file needs a name.");
        }
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    private static String cleanMime(String mime) {
        String value = mime == null ? "" : mime.strip().toLowerCase(Locale.ROOT);
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).strip();
        }
        if (value.isEmpty()) {
            return "application/octet-stream";
        }
        if (value.length() > 127 || !MIME.matcher(value).matches()) {
            throw ApiException.badRequest("invalid_upload", "That file type isn't valid.");
        }
        return value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
