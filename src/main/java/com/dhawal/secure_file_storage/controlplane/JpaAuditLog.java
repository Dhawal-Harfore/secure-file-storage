package com.dhawal.secure_file_storage.controlplane;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import com.dhawal.secure_file_storage.persistence.AuditEventRepository;
import com.dhawal.secure_file_storage.persistence.StoredFileRepository;
import com.dhawal.secure_file_storage.persistence.UserAccountRepository;

@Service
@ConditionalOnMissingBean(AuditLog.class)
public class JpaAuditLog implements AuditLog {

    private final AuditEventRepository events;
    private final UserAccountRepository users;
    private final StoredFileRepository files;

    public JpaAuditLog(AuditEventRepository events, UserAccountRepository users,
            StoredFileRepository files) {
        this.events = events;
        this.users = users;
        this.files = files;
    }

    @Override
    @Transactional
    public void record(AuditEvent event) {
        var actor = event.actorId() == null ? null : users.findById(event.actorId()).orElse(null);
        var file = event.fileId() == null ? null : files.findById(event.fileId()).orElse(null);
        events.save(new com.dhawal.secure_file_storage.persistence.AuditEvent(
                event.id(), actor, event.actorEmail(), file, event.fileName(),
                event.action(), event.result(),
                event.qualityTier() == null ? null
                        : com.dhawal.secure_file_storage.persistence.QualityTier.valueOf(
                                event.qualityTier().name()),
                null, event.ip(), event.userAgent()));
    }
}
