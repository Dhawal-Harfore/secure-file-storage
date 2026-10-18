package com.dhawal.secure_file_storage.controlplane;

import java.util.Optional;

public interface FileCatalog {

    void add(StoredFile file);

    Optional<StoredFile> find(String fileId);

    void addRendition(String fileId, Rendition rendition);
}
