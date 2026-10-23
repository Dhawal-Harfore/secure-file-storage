package com.dhawal.secure_file_storage.controlplane;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.persistence.AuditEventRepository;
import com.dhawal.secure_file_storage.storage.ApiException;

@Service
public class AuditQueryService {

    public record AuditPage(List<AuditEvent> events, long total, int page, int pageSize) {
    }

    private final AuditEventRepository events;

    public AuditQueryService(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional(readOnly = true)
    public AuditPage search(SessionUser user, String fileId, String action, String result,
            String from, String to, Integer page, Integer pageSize) {

        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 25 : Math.max(1, Math.min(100, pageSize));

        Page<com.dhawal.secure_file_storage.persistence.AuditEvent> found = events.search(user.id(),
                blank(fileId), blank(action), blank(result), instant(from), instant(to),
                PageRequest.of(p - 1, size, Sort.by(Sort.Direction.DESC, "at")));

        return new AuditPage(found.getContent().stream().map(AuditQueryService::view).toList(),
                found.getTotalElements(), p, size);
    }

    private static AuditEvent view(com.dhawal.secure_file_storage.persistence.AuditEvent e) {
        return new AuditEvent(e.getId(), e.getAt(),
                e.getActor() == null ? null : e.getActor().getId(), e.getActorEmail(),
                e.getFile() == null ? null : e.getFile().getId(), e.getFileName(),
                e.getAction(), e.getResult(),
                e.getQualityTier() == null ? null : QualityTier.valueOf(e.getQualityTier().name()),
                e.getShare() == null ? null : e.getShare().getId(), e.getIp(), e.getUserAgent());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("invalid_request", "Dates have to look like 2026-10-23T00:00:00Z.");
        }
    }
}
