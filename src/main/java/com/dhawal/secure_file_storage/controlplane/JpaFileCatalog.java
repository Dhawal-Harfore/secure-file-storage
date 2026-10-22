package com.dhawal.secure_file_storage.controlplane;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dhawal.secure_file_storage.persistence.StoredFileRepository;
import com.dhawal.secure_file_storage.persistence.UserAccount;
import com.dhawal.secure_file_storage.persistence.UserAccountRepository;
import com.dhawal.secure_file_storage.storage.Ids;

@Service
public class JpaFileCatalog implements FileCatalog {

    private final StoredFileRepository files;
    private final UserAccountRepository users;
    private final Map<String, Map<QualityTier, Rendition>> renditions = new ConcurrentHashMap<>();

    public JpaFileCatalog(StoredFileRepository files, UserAccountRepository users) {
        this.files = files;
        this.users = users;
    }

    @Override
    @Transactional
    public void add(StoredFile file) {
        UserAccount owner = users.findByEmail(file.ownerEmail())
                .orElseGet(() -> users.save(new UserAccount(file.ownerId(), file.ownerEmail())));
        if (!owner.getId().equals(file.ownerId())) {
            throw new IllegalStateException("Authenticated user does not match file owner.");
        }

        files.save(new com.dhawal.secure_file_storage.persistence.StoredFile(
                file.id(), file.name(), file.mime(), file.size(),
                com.dhawal.secure_file_storage.persistence.FileKind.valueOf(file.kind().name()),
                owner));
        renditions.put(file.id(), new ConcurrentHashMap<>(file.renditions()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredFile> find(String fileId) {
        return files.findById(fileId).map(file -> toControlPlane(file));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredFile> list() {
        return files.findAll().stream().map(this::toControlPlane).toList();
    }

    @Override
    public void addRendition(String fileId, Rendition rendition) {
        renditions.computeIfAbsent(fileId, ignored -> new ConcurrentHashMap<>())
                .put(rendition.tier(), rendition);
    }

    private StoredFile toControlPlane(com.dhawal.secure_file_storage.persistence.StoredFile file) {
        Map<QualityTier, Rendition> copies = renditions.getOrDefault(file.getId(), Map.of());
        return new StoredFile(file.getId(), file.getName(), file.getMime(), file.getSize(),
                FileKind.valueOf(file.getKind().name()), file.getOwner().getId(),
                file.getOwner().getEmail(), file.getCreatedAt(), file.getUpdatedAt(), Map.copyOf(copies));
    }
}
