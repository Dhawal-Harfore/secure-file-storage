package com.dhawal.secure_file_storage.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FileRenditionRepository extends JpaRepository<FileRendition, FileRendition.Key> {

    List<FileRendition> findByFileId(String fileId);
}
