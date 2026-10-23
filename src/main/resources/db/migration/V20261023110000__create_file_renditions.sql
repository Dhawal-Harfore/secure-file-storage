CREATE TABLE file_renditions (
    file_id VARCHAR(40) NOT NULL,
    tier VARCHAR(16) NOT NULL,
    mime VARCHAR(255) NOT NULL,
    size BIGINT NOT NULL,
    key_handle VARCHAR(80) NOT NULL,
    CONSTRAINT pk_file_renditions PRIMARY KEY (file_id, tier),
    CONSTRAINT ck_file_renditions_size CHECK (size >= 0),
    CONSTRAINT fk_file_renditions_file FOREIGN KEY (file_id) REFERENCES stored_files (id) ON DELETE CASCADE
);

INSERT INTO file_renditions (file_id, tier, mime, size, key_handle)
SELECT id, 'ORIGINAL', mime, size, CONCAT('kh_', SUBSTRING(id, 3), '_original')
FROM stored_files;
