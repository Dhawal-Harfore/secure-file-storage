package com.dhawal.secure_file_storage.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShareRepository extends JpaRepository<Share, String> {

    Optional<Share> findByTokenHash(String tokenHash);
}
