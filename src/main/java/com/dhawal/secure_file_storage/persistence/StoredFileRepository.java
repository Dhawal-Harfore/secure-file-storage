package com.dhawal.secure_file_storage.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, String> {

    List<StoredFile> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<StoredFile> findByDeletedAtIsNullOrderByCreatedAtDesc();
}
