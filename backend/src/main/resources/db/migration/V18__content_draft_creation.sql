CREATE TABLE content_draft_creation_request (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_snapshot JSON NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_draft_creation_scope (created_by, key_hash),
    KEY idx_draft_creation_expiry (expires_at),
    CONSTRAINT fk_draft_creation_actor FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_draft_creation_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_draft_creation_revision FOREIGN KEY (revision_id, content_id) REFERENCES content_revision(id, content_id),
    CONSTRAINT chk_draft_creation_state CHECK (state IN ('PROCESSING','SUCCEEDED')),
    CONSTRAINT chk_draft_creation_snapshot CHECK (
        (state='PROCESSING' AND content_id IS NULL AND revision_id IS NULL AND result_snapshot IS NULL
            AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state='SUCCEEDED' AND content_id IS NOT NULL AND revision_id IS NOT NULL AND result_snapshot IS NOT NULL
            AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
