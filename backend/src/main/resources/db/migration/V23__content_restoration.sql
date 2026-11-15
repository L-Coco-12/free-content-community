CREATE TABLE content_restoration_request (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_snapshot JSON NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_restoration_scope (created_by, key_hash),
    KEY idx_restoration_expiry (expires_at),
    CONSTRAINT fk_restoration_actor FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_restoration_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT chk_restoration_state CHECK (state IN ('PROCESSING','SUCCEEDED')),
    CONSTRAINT chk_restoration_snapshot CHECK (
        (state='PROCESSING' AND content_id IS NULL AND result_snapshot IS NULL
            AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state='SUCCEEDED' AND content_id IS NOT NULL AND result_snapshot IS NOT NULL
            AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE content_restoration_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    before_publication_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    after_publication_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    before_content_version INT NOT NULL,
    after_content_version INT NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_restoration_version (content_id,after_content_version),
    KEY idx_content_restoration_actor (actor_id,occurred_at,id),
    CONSTRAINT fk_content_restoration_actor FOREIGN KEY (actor_id) REFERENCES app_user(id),
    CONSTRAINT fk_content_restoration_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_content_restoration_published FOREIGN KEY (published_revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT chk_content_restoration_version CHECK (before_content_version>=1 AND after_content_version=before_content_version+1),
    CONSTRAINT chk_content_restoration_before CHECK (before_publication_status IN ('UNPUBLISHED','PUBLISHED','WITHDRAWN','TAKEN_DOWN')),
    CONSTRAINT chk_content_restoration_after CHECK (
        (before_publication_status='TAKEN_DOWN' AND after_publication_status='TAKEN_DOWN')
        OR (before_publication_status!='TAKEN_DOWN' AND after_publication_status IN ('UNPUBLISHED','WITHDRAWN'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE content_lifecycle_outbox DROP CHECK chk_content_lifecycle_outbox_type;
ALTER TABLE content_lifecycle_outbox ADD CONSTRAINT chk_content_lifecycle_outbox_type
    CHECK (event_type IN ('CONTENT_DELETED','CONTENT_RESTORED'));
