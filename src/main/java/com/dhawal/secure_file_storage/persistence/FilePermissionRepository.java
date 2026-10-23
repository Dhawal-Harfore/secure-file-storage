package com.dhawal.secure_file_storage.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FilePermissionRepository extends JpaRepository<FilePermission, String> {

    List<FilePermission> findBySubjectId(String subjectId);

    Optional<FilePermission> findByFileIdAndSubjectId(String fileId, String subjectId);

    List<FilePermission> findByFileId(String fileId);
}
