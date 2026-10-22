package com.dhawal.secure_file_storage.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShareRepository extends JpaRepository<Share, String> {

    Optional<Share> findByTokenHash(String tokenHash);

    List<Share> findByFileIdOrderByCreatedAtDesc(String fileId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Share s set s.viewsUsed = s.viewsUsed + 1
            where s.id = :id and s.revokedAt is null
              and (s.expiresAt is null or s.expiresAt > :now)
              and (s.maxViews is null or s.viewsUsed < s.maxViews)
            """)
    int countView(@Param("id") String id, @Param("now") Instant now);
}
