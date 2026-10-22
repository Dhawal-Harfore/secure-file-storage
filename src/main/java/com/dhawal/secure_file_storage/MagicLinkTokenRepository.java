package com.dhawal.secure_file_storage;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MagicLinkTokenRepository extends JpaRepository<MagicLinkToken, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MagicLinkToken t set t.usedAt = :now where t.tokenHash = :hash and t.usedAt is null")
    int markUsed(@Param("hash") String hash, @Param("now") Instant now);

    @Modifying
    @Query("delete from MagicLinkToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
