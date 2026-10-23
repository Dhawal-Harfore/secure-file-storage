package com.dhawal.secure_file_storage.controlplane;

import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dhawal.secure_file_storage.persistence.FileRendition;
import com.dhawal.secure_file_storage.persistence.FileRenditionRepository;
import com.dhawal.secure_file_storage.persistence.StoredFileRepository;
import com.dhawal.secure_file_storage.persistence.UserAccount;
import com.dhawal.secure_file_storage.persistence.UserAccountRepository;
import com.dhawal.secure_file_storage.storage.Ids;

@Service
public class JpaFileCatalog implements FileCatalog {

    private final StoredFileRepository files;
    private final UserAccountRepository users;
    private final FileRenditionRepository renditions;

    public JpaFileCatalog(StoredFileRepository files, UserAccountRepository users,
            FileRenditionRepository renditions) {
        this.files = files;
        this.users = users;
        this.renditions = renditions;
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
        file.renditions().values().forEach(r -> renditions.save(toRow(file.id(), r)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredFile> find(String fileId) {
        return files.findById(fileId).filter(file -> file.getDeletedAt() == null).map(this::toControlPlane);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredFile> list() {
        return files.findByDeletedAtIsNullOrderByCreatedAtDesc().stream().map(this::toControlPlane).toList();
    }

    @Override
    @Transactional
    public void addRendition(String fileId, Rendition rendition) {
        renditions.save(toRow(fileId, rendition));
    }

    @Override
    @Transactional
    public void rename(String fileId, String name) {
        files.findById(fileId).filter(file -> file.getDeletedAt() == null).ifPresent(file -> {
            file.rename(name);
            files.saveAndFlush(file);
        });
    }

    @Override
    @Transactional
    public void markDeleted(String fileId) {
        files.findById(fileId).ifPresent(file -> {
            file.markDeleted(java.time.Instant.now());
            files.saveAndFlush(file);
        });
    }

    private static FileRendition toRow(String fileId, Rendition rendition) {
        return new FileRendition(fileId,
                com.dhawal.secure_file_storage.persistence.QualityTier.valueOf(rendition.tier().name()),
                rendition.mime(), rendition.size(), rendition.keyHandle());
    }

    private StoredFile toControlPlane(com.dhawal.secure_file_storage.persistence.StoredFile file) {
        Map<QualityTier, Rendition> copies = new EnumMap<>(QualityTier.class);
        for (FileRendition row : renditions.findByFileId(file.getId())) {
            QualityTier tier = QualityTier.valueOf(row.getTier().name());
            copies.put(tier, new Rendition(tier, row.getMime(), row.getSize(), row.getKeyHandle()));
        }
        return new StoredFile(file.getId(), file.getName(), file.getMime(), file.getSize(),
                FileKind.valueOf(file.getKind().name()), file.getOwner().getId(),
                file.getOwner().getEmail(), file.getCreatedAt(), file.getUpdatedAt(), Map.copyOf(copies));
    }
}
