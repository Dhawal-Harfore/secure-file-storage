package com.dhawal.secure_file_storage.controlplane;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import com.dhawal.secure_file_storage.persistence.AccessLevel;
import com.dhawal.secure_file_storage.persistence.Action;
import com.dhawal.secure_file_storage.persistence.FilePermission;
import com.dhawal.secure_file_storage.persistence.FilePermissionRepository;
import com.dhawal.secure_file_storage.persistence.StoredFileRepository;

@Service
@ConditionalOnMissingBean(PermitService.class)
public class JpaPermitService implements PermitService {

    private final StoredFileRepository files;
    private final FilePermissionRepository permissions;

    public JpaPermitService(StoredFileRepository files, FilePermissionRepository permissions) {
        this.files = files;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public Permit evaluate(String fileId, String subjectId) {
        Optional<com.dhawal.secure_file_storage.persistence.StoredFile> found = files.findById(fileId);
        if (found.isEmpty()) {
            return empty(fileId, subjectId, "policy:not_found");
        }

        com.dhawal.secure_file_storage.persistence.StoredFile file = found.get();
        if (file.getOwner().getId().equals(subjectId)) {
            return permit(fileId, subjectId, AccessLevel.OWNER,
                    List.of(Action.VIEW, Action.DOWNLOAD, Action.EDIT,
                            Action.SHARE, Action.REVOKE, Action.DELETE),
                    QualityTier.ORIGINAL, "policy:owner");
        }

        Optional<FilePermission> permission =
                permissions.findByFileIdAndSubjectId(fileId, subjectId);
        if (permission.isEmpty()) {
            return empty(fileId, subjectId, "policy:none");
        }

        FilePermission grant = permission.get();
        QualityTier tier = switch (grant.getAccessLevel()) {
            case EDITOR -> QualityTier.ORIGINAL;
            case VIEWER -> QualityTier.REDUCED;
            case GUEST -> QualityTier.LOWEST;
            case OWNER -> QualityTier.ORIGINAL;
        };
        return permit(fileId, subjectId, grant.getAccessLevel(),
                grant.getActions(), tier, "policy:" + grant.getAccessLevel().name().toLowerCase());
    }

    private static Permit permit(String fileId, String subjectId, AccessLevel level,
            List<Action> actions, QualityTier tier, String reason) {
        List<String> names = actions.stream().map(action -> action.name().toLowerCase()).toList();
        return new Permit(fileId, subjectId, level.name().toLowerCase(), names, tier,
                tier == QualityTier.METADATA ? null : "kh_" + fileId.substring(2) + "_" + tier.wire(),
                null, null, null, reason);
    }

    private static Permit permit(String fileId, String subjectId, AccessLevel level,
            java.util.Set<Action> actions, QualityTier tier, String reason) {
        return permit(fileId, subjectId, level, actions.stream().toList(), tier, reason);
    }

    private static Permit empty(String fileId, String subjectId, String reason) {
        return new Permit(fileId, subjectId, null, List.of(), QualityTier.METADATA,
                null, null, null, null, reason);
    }
}
