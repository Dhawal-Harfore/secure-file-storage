CREATE TABLE app_users (
    id VARCHAR(40) NOT NULL,
    email VARCHAR(254) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_app_users PRIMARY KEY (id),
    CONSTRAINT uk_app_users_email UNIQUE (email)
);

CREATE TABLE stored_files (
    id VARCHAR(40) NOT NULL,
    name VARCHAR(255) NOT NULL,
    mime VARCHAR(255) NOT NULL,
    size BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    owner_id VARCHAR(40) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_stored_files PRIMARY KEY (id),
    CONSTRAINT ck_stored_files_size CHECK (size >= 0),
    CONSTRAINT fk_stored_files_owner FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE INDEX ix_stored_files_owner ON stored_files (owner_id);
CREATE INDEX ix_stored_files_kind ON stored_files (kind);

CREATE TABLE file_permissions (
    id VARCHAR(40) NOT NULL,
    file_id VARCHAR(40) NOT NULL,
    subject_id VARCHAR(40) NOT NULL,
    access_level VARCHAR(16) NOT NULL,
    actions VARCHAR(128) NOT NULL,
    CONSTRAINT pk_file_permissions PRIMARY KEY (id),
    CONSTRAINT uk_file_permission_subject UNIQUE (file_id, subject_id),
    CONSTRAINT fk_file_permissions_file FOREIGN KEY (file_id) REFERENCES stored_files (id),
    CONSTRAINT fk_file_permissions_subject FOREIGN KEY (subject_id) REFERENCES app_users (id)
);

CREATE INDEX ix_file_permissions_subject ON file_permissions (subject_id);

CREATE TABLE shares (
    id VARCHAR(40) NOT NULL,
    file_id VARCHAR(40) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    access_level VARCHAR(16) NOT NULL,
    actions VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    max_views INT NULL,
    views_used INT NOT NULL DEFAULT 0,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(40) NOT NULL,
    CONSTRAINT pk_shares PRIMARY KEY (id),
    CONSTRAINT uk_share_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_shares_max_views CHECK (max_views IS NULL OR max_views > 0),
    CONSTRAINT ck_shares_views_used CHECK (views_used >= 0),
    CONSTRAINT fk_shares_file FOREIGN KEY (file_id) REFERENCES stored_files (id),
    CONSTRAINT fk_shares_creator FOREIGN KEY (created_by) REFERENCES app_users (id)
);

CREATE INDEX ix_shares_file ON shares (file_id);
CREATE INDEX ix_shares_expires_at ON shares (expires_at);

CREATE TABLE audit_events (
    id VARCHAR(40) NOT NULL,
    at_time TIMESTAMP(6) NOT NULL,
    actor_id VARCHAR(40) NULL,
    actor_email VARCHAR(254) NULL,
    file_id VARCHAR(40) NULL,
    file_name VARCHAR(255) NULL,
    action VARCHAR(32) NOT NULL,
    result VARCHAR(32) NOT NULL,
    quality_tier VARCHAR(16) NULL,
    share_id VARCHAR(40) NULL,
    ip VARCHAR(45) NULL,
    user_agent VARCHAR(512) NULL,
    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    CONSTRAINT fk_audit_events_actor FOREIGN KEY (actor_id) REFERENCES app_users (id),
    CONSTRAINT fk_audit_events_file FOREIGN KEY (file_id) REFERENCES stored_files (id),
    CONSTRAINT fk_audit_events_share FOREIGN KEY (share_id) REFERENCES shares (id)
);

CREATE INDEX ix_audit_events_at ON audit_events (at_time);
CREATE INDEX ix_audit_events_actor ON audit_events (actor_id);
CREATE INDEX ix_audit_events_file ON audit_events (file_id);
CREATE INDEX ix_audit_events_action_result ON audit_events (action, result);
