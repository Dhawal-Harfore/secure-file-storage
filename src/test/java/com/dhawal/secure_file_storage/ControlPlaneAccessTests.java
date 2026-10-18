package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;

import com.dhawal.secure_file_storage.controlplane.AuditEvent;
import com.dhawal.secure_file_storage.controlplane.JpaAuditLog;
import com.dhawal.secure_file_storage.controlplane.JpaFileCatalog;
import com.dhawal.secure_file_storage.controlplane.JpaPermitService;
import com.dhawal.secure_file_storage.controlplane.Permit;
import com.dhawal.secure_file_storage.controlplane.QualityTier;
import com.dhawal.secure_file_storage.controlplane.Rendition;
import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.persistence.AccessLevel;
import com.dhawal.secure_file_storage.persistence.Action;
import com.dhawal.secure_file_storage.persistence.AuditEventRepository;
import com.dhawal.secure_file_storage.persistence.FilePermission;
import com.dhawal.secure_file_storage.persistence.FilePermissionRepository;
import com.dhawal.secure_file_storage.persistence.FileKind;
import com.dhawal.secure_file_storage.persistence.StoredFileRepository;
import com.dhawal.secure_file_storage.persistence.UserAccountRepository;
import com.dhawal.secure_file_storage.security.AuthenticatedUserService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ControlPlaneAccessTests {

    private JpaFileCatalog catalog;

    private JpaPermitService permits;

    private JpaAuditLog audit;

    @Autowired
    private StoredFileRepository files;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private FilePermissionRepository permissions;

    @Autowired
    private AuditEventRepository events;

    private AuthenticatedUserService authenticatedUsers;

    @BeforeEach
    void setUpServices() {
        authenticatedUsers = new AuthenticatedUserService(users);
        catalog = new JpaFileCatalog(files, users);
        permits = new JpaPermitService(files, permissions);
        audit = new JpaAuditLog(events, users, files);
    }

    @Test
    void authenticatedEmailResolvesToPersistentAccountAndOwnerIsAllowed() {
        SessionUser owner = authenticatedUsers.resolve("owner@example.com");
        assertThat(files.findById(owner.id())).isEmpty();

        catalog.add(new com.dhawal.secure_file_storage.controlplane.StoredFile(
                "f_owner", "owner.pdf", "application/pdf", 10,
                com.dhawal.secure_file_storage.controlplane.FileKind.PDF,
                owner.id(), owner.email(), Instant.now(), Instant.now(),
                Map.of(QualityTier.ORIGINAL,
                        new Rendition(QualityTier.ORIGINAL, "application/pdf", 10, "kh_owner"))));

        Permit permit = permits.evaluate("f_owner", owner.id());
        assertThat(permit.accessLevel()).isEqualTo("owner");
        assertThat(permit.actions()).containsExactlyInAnyOrder(
                "view", "download", "edit", "share", "revoke", "delete");
        assertThat(files.findById("f_owner")).isPresent();
    }

    @Test
    void explicitPermissionIsAllowedAndUnsharedUserIsDenied() {
        SessionUser owner = authenticatedUsers.resolve("owner2@example.com");
        SessionUser viewer = authenticatedUsers.resolve("viewer@example.com");
        SessionUser stranger = authenticatedUsers.resolve("stranger@example.com");
        catalog.add(new com.dhawal.secure_file_storage.controlplane.StoredFile(
                "f_shared", "shared.txt", "text/plain", 4,
                com.dhawal.secure_file_storage.controlplane.FileKind.OTHER,
                owner.id(), owner.email(), Instant.now(), Instant.now(), Map.of()));

        com.dhawal.secure_file_storage.persistence.StoredFile stored = files.findById("f_shared").orElseThrow();
        permissions.save(new FilePermission("p_viewer", stored,
                users.getReferenceById(viewer.id()), AccessLevel.VIEWER,
                EnumSet.of(Action.VIEW)));

        Permit allowed = permits.evaluate("f_shared", viewer.id());
        Permit denied = permits.evaluate("f_shared", stranger.id());
        assertThat(allowed.accessLevel()).isEqualTo("viewer");
        assertThat(allowed.allows("view")).isTrue();
        assertThat(allowed.allows("download")).isFalse();
        assertThat(denied.actions()).isEmpty();
        assertThat(denied.qualityTier()).isEqualTo(QualityTier.METADATA);
    }

    @Test
    void auditEventsArePersistedWithTheResolvedActorAndFile() {
        SessionUser owner = authenticatedUsers.resolve("auditor@example.com");
        catalog.add(new com.dhawal.secure_file_storage.controlplane.StoredFile(
                "f_audit", "audit.pdf", "application/pdf", 8,
                com.dhawal.secure_file_storage.controlplane.FileKind.PDF,
                owner.id(), owner.email(), Instant.now(), Instant.now(), Map.of()));

        audit.record(new AuditEvent("a_audit", Instant.now(), owner.id(), owner.email(),
                "f_audit", "audit.pdf", "view", "permit", QualityTier.METADATA,
                null, "127.0.0.1", "test-agent"));

        assertThat(events.findById("a_audit")).get()
                .extracting(event -> event.getActor().getId(), event -> event.getFile().getId(),
                        event -> event.getResult())
                .containsExactly(owner.id(), "f_audit", "permit");
    }
}
