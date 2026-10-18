package com.dhawal.secure_file_storage.controlplane;

import java.util.Optional;
import java.util.List;

public interface FileCatalog {

    void add(StoredFile file);

    Optional<StoredFile> find(String fileId);

    default List<StoredFile> list() {
        return List.of();
    }

    void addRendition(String fileId, Rendition rendition);
}
