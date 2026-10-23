package com.dhawal.secure_file_storage.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dhawal.secure_file_storage.controlplane.AuditEvent;
import com.dhawal.secure_file_storage.controlplane.Permit;
import com.dhawal.secure_file_storage.controlplane.Rendition;
import com.dhawal.secure_file_storage.controlplane.StoredFile;
import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.persistence.ShareRepository;

@Service
public class FileManagementService {

    private static final Logger log = LoggerFactory.getLogger(FileManagementService.class);

    private final ControlPlane controlPlane;
    private final FileStore store;
    private final ShareRepository shares;

    public FileManagementService(ControlPlane controlPlane, FileStore store, ShareRepository shares) {
        this.controlPlane = controlPlane;
        this.store = store;
        this.shares = shares;
    }

    public StoredFile rename(SessionUser user, String fileId, String name, RequestInfo info) {
        StoredFile file = require(user, fileId, "edit");
        String clean = UploadService.cleanName(name);
        controlPlane.catalog().rename(fileId, clean);
        record(user, file, "edit", info);
        return controlPlane.catalog().find(fileId)
                .orElseThrow(() -> ApiException.notFound("That file doesn't exist."));
    }

    @Transactional
    public void delete(SessionUser user, String fileId, RequestInfo info) throws IOException {
        StoredFile file = require(user, fileId, "delete");

        controlPlane.catalog().markDeleted(fileId);
        shares.revokeAllForFile(fileId, Instant.now());

        for (Rendition rendition : file.renditions().values()) {
            controlPlane.cipher().destroy(rendition.keyHandle());
        }
        try {
            FileStore.deleteTree(store.fileDir(fileId));
            Files.deleteIfExists(store.job(fileId));
        } catch (RuntimeException e) {
            log.warn("Keys for {} are destroyed but its encrypted bytes could not all be removed", fileId, e);
        }
        record(user, file, "delete", info);
    }

    private StoredFile require(SessionUser user, String fileId, String action) {
        StoredFile file = controlPlane.catalog().find(fileId)
                .orElseThrow(() -> ApiException.notFound("That file doesn't exist."));
        Permit permit = controlPlane.permits().evaluate(fileId, user.id());
        if (permit == null || permit.actions() == null || permit.actions().isEmpty()) {
            throw ApiException.notFound("That file doesn't exist.");
        }
        if (!permit.allows(action)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "denied",
                    "You don't have permission to " + action + " this file.", permit.reason());
        }
        return file;
    }

    private void record(SessionUser user, StoredFile file, String action, RequestInfo info) {
        controlPlane.audit().record(new AuditEvent(Ids.newEventId(), Instant.now(), user.id(), user.email(),
                file.id(), file.name(), action, "permit", null, null, info.ip(), info.userAgent()));
    }
}
