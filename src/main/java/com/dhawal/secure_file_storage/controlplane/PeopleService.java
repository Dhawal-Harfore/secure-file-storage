package com.dhawal.secure_file_storage.controlplane;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.persistence.AccessLevel;
import com.dhawal.secure_file_storage.persistence.Action;
import com.dhawal.secure_file_storage.persistence.FilePermission;
import com.dhawal.secure_file_storage.persistence.FilePermissionRepository;
import com.dhawal.secure_file_storage.persistence.StoredFileRepository;
import com.dhawal.secure_file_storage.persistence.UserAccount;
import com.dhawal.secure_file_storage.persistence.UserAccountRepository;
import com.dhawal.secure_file_storage.storage.ApiException;
import com.dhawal.secure_file_storage.storage.ControlPlane;
import com.dhawal.secure_file_storage.storage.Ids;
import com.dhawal.secure_file_storage.storage.RequestInfo;

@Service
public class PeopleService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Person(String id, String email, String accessLevel, List<String> actions) {
    }

    public record GrantRequest(String email, String accessLevel, Boolean download) {
    }

    private final FilePermissionRepository permissions;
    private final StoredFileRepository files;
    private final UserAccountRepository users;
    private final ControlPlane controlPlane;

    public PeopleService(FilePermissionRepository permissions, StoredFileRepository files,
            UserAccountRepository users, ControlPlane controlPlane) {
        this.permissions = permissions;
        this.files = files;
        this.users = users;
        this.controlPlane = controlPlane;
    }

    @Transactional(readOnly = true)
    public List<Person> list(SessionUser owner, String fileId) {
        require(owner, fileId, "share");
        return permissions.findByFileId(fileId).stream().map(PeopleService::person).toList();
    }

    @Transactional
    public Person grant(SessionUser owner, String fileId, GrantRequest request, RequestInfo info) {
        StoredFile file = require(owner, fileId, "share");

        String email = request.email() == null ? "" : request.email().strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw ApiException.badRequest("invalid_person", "Enter the email address of the person.");
        }
        if (email.equals(file.ownerEmail())) {
            throw ApiException.badRequest("invalid_person", "The owner already has full access.");
        }

        AccessLevel level = switch (request.accessLevel() == null ? "" : request.accessLevel().strip().toLowerCase(Locale.ROOT)) {
            case "editor" -> AccessLevel.EDITOR;
            case "viewer" -> AccessLevel.VIEWER;
            case "guest" -> AccessLevel.GUEST;
            default -> throw ApiException.badRequest("invalid_person", "Pick editor, viewer or guest.");
        };
        Set<Action> actions = switch (level) {
            case EDITOR -> EnumSet.of(Action.VIEW, Action.EDIT, Action.DOWNLOAD);
            case VIEWER -> Boolean.TRUE.equals(request.download())
                    ? EnumSet.of(Action.VIEW, Action.DOWNLOAD) : EnumSet.of(Action.VIEW);
            default -> EnumSet.of(Action.VIEW);
        };

        SessionUser subject = SessionUser.of(email);
        UserAccount account = users.findByEmail(email)
                .orElseGet(() -> users.save(new UserAccount(subject.id(), email)));

        FilePermission permission = permissions.findByFileIdAndSubjectId(fileId, account.getId())
                .map(existing -> {
                    existing.change(level, actions);
                    return existing;
                })
                .orElseGet(() -> new FilePermission(newId(), files.getReferenceById(fileId), account, level, actions));
        permissions.saveAndFlush(permission);

        record(owner, file, "share", info);
        return person(permission);
    }

    @Transactional
    public void remove(SessionUser owner, String fileId, String permissionId, RequestInfo info) {
        StoredFile file = require(owner, fileId, "revoke");
        FilePermission permission = permissions.findById(permissionId)
                .filter(p -> p.getFile().getId().equals(fileId))
                .orElseThrow(() -> ApiException.notFound("That person doesn't have access."));
        permissions.delete(permission);
        record(owner, file, "revoke", info);
    }

    private StoredFile require(SessionUser user, String fileId, String action) {
        StoredFile file = controlPlane.catalog().find(fileId)
                .orElseThrow(() -> ApiException.notFound("That file doesn't exist."));
        Permit permit = controlPlane.permits().evaluate(fileId, user.id());
        if (permit == null || permit.actions() == null || permit.actions().isEmpty()) {
            throw ApiException.notFound("That file doesn't exist.");
        }
        if (!permit.allows(action)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "denied",
                    "You don't have permission to change who can open this file.", permit.reason());
        }
        return file;
    }

    private void record(SessionUser owner, StoredFile file, String action, RequestInfo info) {
        controlPlane.audit().record(new AuditEvent(Ids.newEventId(), Instant.now(), owner.id(), owner.email(),
                file.id(), file.name(), action, "permit", null, null, info.ip(), info.userAgent()));
    }

    private static Person person(FilePermission permission) {
        return new Person(permission.getId(), permission.getSubject().getEmail(),
                permission.getAccessLevel().name().toLowerCase(Locale.ROOT),
                permission.getActions().stream().map(a -> a.name().toLowerCase(Locale.ROOT)).sorted().toList());
    }

    private static String newId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "p_" + HexFormat.of().formatHex(bytes);
    }
}
