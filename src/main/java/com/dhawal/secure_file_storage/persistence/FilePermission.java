package com.dhawal.secure_file_storage.persistence;

import java.util.EnumSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "file_permissions",
        uniqueConstraints = @UniqueConstraint(name = "uk_file_permission_subject",
                columnNames = {"file_id", "subject_id"}))
public class FilePermission {

    @Id
    @Column(length = 40, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private StoredFile file;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private UserAccount subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", length = 16, nullable = false)
    private AccessLevel accessLevel;

    @Convert(converter = ActionSetConverter.class)
    @Column(length = 128, nullable = false)
    private Set<Action> actions = EnumSet.noneOf(Action.class);

    protected FilePermission() {
    }

    public FilePermission(String id, StoredFile file, UserAccount subject,
            AccessLevel accessLevel, Set<Action> actions) {
        this.id = id;
        this.file = file;
        this.subject = subject;
        this.accessLevel = accessLevel;
        this.actions = actions.isEmpty()
                ? EnumSet.noneOf(Action.class)
                : EnumSet.copyOf(actions);
    }

    public String getId() {
        return id;
    }

    public StoredFile getFile() {
        return file;
    }

    public UserAccount getSubject() {
        return subject;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public Set<Action> getActions() {
        return Set.copyOf(actions);
    }

    public void change(AccessLevel accessLevel, Set<Action> actions) {
        this.accessLevel = accessLevel;
        this.actions = actions.isEmpty() ? EnumSet.noneOf(Action.class) : EnumSet.copyOf(actions);
    }
}
