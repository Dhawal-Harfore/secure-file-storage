package com.dhawal.secure_file_storage.persistence;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {

    @Query(value = """
            select e from AuditEvent e
            left join e.file f
            left join f.owner o
            left join e.actor a
            where (o.id = :me or a.id = :me)
              and (:fileId is null or f.id = :fileId)
              and (:action is null or e.action = :action)
              and (:result is null or e.result = :result)
              and (:from is null or e.at >= :from)
              and (:to is null or e.at < :to)
            """,
            countQuery = """
            select count(e) from AuditEvent e
            left join e.file f
            left join f.owner o
            left join e.actor a
            where (o.id = :me or a.id = :me)
              and (:fileId is null or f.id = :fileId)
              and (:action is null or e.action = :action)
              and (:result is null or e.result = :result)
              and (:from is null or e.at >= :from)
              and (:to is null or e.at < :to)
            """)
    Page<AuditEvent> search(@Param("me") String me, @Param("fileId") String fileId,
            @Param("action") String action, @Param("result") String result,
            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
