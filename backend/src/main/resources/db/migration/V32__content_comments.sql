CREATE TABLE content_comment (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    root_comment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reply_to_comment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    body VARCHAR(2000) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_comment_content_id (content_id, id),
    KEY idx_comment_content_state (content_id, state),
    KEY idx_comment_thread (content_id, root_comment_id, state, created_at, id),
    KEY idx_comment_reply_target (content_id, reply_to_comment_id),
    CONSTRAINT fk_comment_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_comment_author FOREIGN KEY (author_id) REFERENCES app_user(id),
    CONSTRAINT fk_comment_root FOREIGN KEY (content_id, root_comment_id) REFERENCES content_comment(content_id, id),
    CONSTRAINT fk_comment_reply_target FOREIGN KEY (content_id, reply_to_comment_id) REFERENCES content_comment(content_id, id),
    CONSTRAINT chk_comment_thread CHECK (
        (root_comment_id IS NULL AND reply_to_comment_id IS NULL) OR
        (root_comment_id IS NOT NULL AND reply_to_comment_id IS NOT NULL AND root_comment_id <> id AND reply_to_comment_id <> id)
    ),
    CONSTRAINT chk_comment_state CHECK (state IN ('VISIBLE','HIDDEN','DELETED')),
    CONSTRAINT chk_comment_body CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 2000),
    CONSTRAINT chk_comment_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE comment_creation_request (
    created_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    comment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_snapshot JSON NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (created_by, key_hash),
    KEY idx_comment_creation_expiry (expires_at),
    CONSTRAINT fk_comment_creation_actor FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_comment_creation_result FOREIGN KEY (comment_id) REFERENCES content_comment(id),
    CONSTRAINT chk_comment_creation_result CHECK (
        (comment_id IS NULL AND result_snapshot IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR
        (comment_id IS NOT NULL AND result_snapshot IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
