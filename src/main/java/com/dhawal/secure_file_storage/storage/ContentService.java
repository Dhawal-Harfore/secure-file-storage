package com.dhawal.secure_file_storage.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.dhawal.secure_file_storage.controlplane.AuditEvent;
import com.dhawal.secure_file_storage.controlplane.FileKind;
import com.dhawal.secure_file_storage.controlplane.Permit;
import com.dhawal.secure_file_storage.controlplane.QualityTier;
import com.dhawal.secure_file_storage.controlplane.Rendition;
import com.dhawal.secure_file_storage.controlplane.StoredFile;
import com.dhawal.secure_file_storage.dto.SessionUser;

@Service
public class ContentService {

    private static final Set<String> INLINE_TYPES = Set.of("application/pdf", "text/plain");

    public record Delivery(StoredFile file, Rendition rendition, Permit permit) {

        public boolean inline() {
            String mime = rendition.mime();
            return rendition.tier() != QualityTier.ORIGINAL
                    || INLINE_TYPES.contains(mime)
                    || mime.startsWith("video/")
                    || mime.startsWith("audio/")
                    || (mime.startsWith("image/") && !mime.startsWith("image/svg"));
        }

        public String contentType() {
            return inline() ? rendition.mime() : "application/octet-stream";
        }
    }

    private final FileStore store;
    private final ControlPlane controlPlane;

    public ContentService(FileStore store, ControlPlane controlPlane) {
        this.store = store;
        this.controlPlane = controlPlane;
    }

    public Delivery content(SessionUser subject, String fileId, String requestedTier, RequestInfo request) {

        QualityTier requested = null;
        if (requestedTier != null && !requestedTier.isBlank()) {
            requested = QualityTier.parse(requestedTier);
            if (requested == null) {
                throw ApiException.badRequest("invalid_tier",
                        "Tier must be original, reduced, lowest or metadata.");
            }
        }

        StoredFile file = find(fileId);
        Permit permit = permitFor(subject, file, request);

        QualityTier ceiling = requested == null
                ? permit.qualityTier()
                : QualityTier.lower(requested, permit.qualityTier());

        Rendition rendition = file.bestAtMost(ceiling);
        if (rendition == null) {
            throw ApiException.notFound("There's no copy of this file at your access level yet.");
        }
        return new Delivery(file, rendition, permit);
    }

    public Delivery thumb(SessionUser subject, String fileId, RequestInfo request) {
        StoredFile file = find(fileId);
        if (file.kind() == FileKind.OTHER) {
            throw ApiException.notFound("This kind of file has no preview.");
        }
        permitFor(subject, file, request);
        Rendition lowest = file.renditions().get(QualityTier.LOWEST);
        if (lowest == null) {
            throw ApiException.notFound("The preview isn't ready yet.");
        }
        return new Delivery(file, lowest, null);
    }

    public void write(Delivery delivery, long offset, long length, OutputStream out) throws IOException {
        Rendition rendition = delivery.rendition();
        try (SeekableByteChannel channel = Files.newByteChannel(
                store.blob(delivery.file().id(), rendition.tier()));
                InputStream plain = controlPlane.cipher().decrypt(rendition.keyHandle(), channel, offset)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = plain.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    throw new IOException("Stored copy of " + delivery.file().id() + " ended early");
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
    }

    public void recordServed(SessionUser subject, Delivery delivery, RequestInfo request) {
        controlPlane.audit().record(new AuditEvent(Ids.newEventId(), Instant.now(), subject.id(),
                subject.email(), delivery.file().id(), delivery.file().name(), "view", "permit",
                delivery.rendition().tier(), null, request.ip(), request.userAgent()));
    }

    private StoredFile find(String fileId) {
        controlPlane.cipher();
        controlPlane.audit();
        return controlPlane.catalog().find(fileId)
                .orElseThrow(() -> ApiException.notFound("That file doesn't exist."));
    }

    private Permit permitFor(SessionUser subject, StoredFile file, RequestInfo request) {
        Permit permit = controlPlane.permits().evaluate(file.id(), subject.id());

        if (permit == null || permit.actions() == null || permit.actions().isEmpty()) {
            throw ApiException.notFound("That file doesn't exist.");
        }
        if (!permit.servesBytes()) {
            controlPlane.audit().record(new AuditEvent(Ids.newEventId(), Instant.now(), subject.id(),
                    subject.email(), file.id(), file.name(), "view", "deny",
                    permit.qualityTier(), null, request.ip(), request.userAgent()));
            throw new ApiException(HttpStatus.FORBIDDEN, "denied",
                    "You can see this file's details but not open it.", permit.reason());
        }
        return permit;
    }
}
