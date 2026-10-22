package com.dhawal.secure_file_storage.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PersistenceRepositoryTests {

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private StoredFileRepository files;

    @Autowired
    private FilePermissionRepository permissions;

    @Test
    void userEmailIsUnique() {
        users.saveAndFlush(new UserAccount("u_1", "unique-check@example.com"));

        assertThatThrownBy(() -> users.saveAndFlush(
                new UserAccount("u_2", "unique-check@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void permissionHasOneRecordPerFileAndSubject() {
        UserAccount owner = users.save(new UserAccount("u_owner", "owner@example.com"));
        UserAccount subject = users.save(new UserAccount("u_subject", "subject@example.com"));
        StoredFile file = files.save(new StoredFile("f_1", "report.pdf",
                "application/pdf", 12, FileKind.PDF, owner));

        permissions.saveAndFlush(new FilePermission("p_1", file, subject,
                AccessLevel.VIEWER, EnumSet.of(Action.VIEW)));

        assertThatThrownBy(() -> permissions.saveAndFlush(new FilePermission(
                "p_2", file, subject, AccessLevel.EDITOR,
                EnumSet.of(Action.VIEW, Action.EDIT))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
