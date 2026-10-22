package com.dhawal.secure_file_storage.controlplane;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dhawal.secure_file_storage.persistence.AuditEventRepository;
import com.dhawal.secure_file_storage.persistence.ShareRepository;
import com.dhawal.secure_file_storage.persistence.StoredFileRepository;
import com.dhawal.secure_file_storage.persistence.UserAccountRepository;

@Service
public class JpaAuditLog implements AuditLog {

    private final AuditEventRepository events;
    private final UserAccountRepository users;
    private final StoredFileRepository files;
    private final ShareRepository shares;

    public JpaAuditLog(AuditEventRepository events, UserAccountRepository users,
            StoredFileRepository files, ShareRepository shares) {
        this.events = events;
        this.users = users;
        this.files = files;
        this.shares = shares;
    }

    @Override
    @Transactional
    public void record(AuditEvent event) {
        var actor = event.actorId() == null ? null : users.findById(event.actorId()).orElse(null);
        var file = event.fileId() == null ? null : files.findById(event.fileId()).orElse(null);
        var share = event.shareId() == null ? null : shares.findById(event.shareId()).orElse(null);
        events.save(new com.dhawal.secure_file_storage.persistence.AuditEvent(
                event.id(), actor, event.actorEmail(), file, event.fileName(),
                event.action(), event.result(),
                event.qualityTier() == null ? null
                        : com.dhawal.secure_file_storage.persistence.QualityTier.valueOf(
                                event.qualityTier().name()),
                share, event.ip(), event.userAgent()));
    }
}
