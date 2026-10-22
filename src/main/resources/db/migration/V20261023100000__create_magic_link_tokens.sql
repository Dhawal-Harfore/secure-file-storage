CREATE TABLE magic_link_tokens (
    token_hash VARCHAR(64) NOT NULL,
    email VARCHAR(254) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_magic_link_tokens PRIMARY KEY (token_hash)
);

CREATE INDEX ix_magic_link_tokens_expires_at ON magic_link_tokens (expires_at);
