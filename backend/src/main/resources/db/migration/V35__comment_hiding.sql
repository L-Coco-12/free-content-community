CREATE TABLE comment_moderation_action (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    comment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    before_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    after_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    before_version INT NOT NULL,
    resulting_version INT NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_comment_moderation_version (comment_id, resulting_version),
    KEY idx_comment_moderation_operator (operator_id, created_at, id),
    CONSTRAINT fk_comment_moderation_comment FOREIGN KEY (comment_id) REFERENCES content_comment(id),
    CONSTRAINT fk_comment_moderation_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    CONSTRAINT chk_comment_moderation_transition CHECK (before_state='VISIBLE' AND after_state='HIDDEN'),
    CONSTRAINT chk_comment_moderation_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 1000),
    CONSTRAINT chk_comment_moderation_version CHECK (before_version>=1 AND resulting_version=before_version+1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE comment_hide_request (
    operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (operator_id, key_hash),
    KEY idx_comment_hide_request_expiry (expires_at),
    CONSTRAINT fk_comment_hide_request_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    CONSTRAINT fk_comment_hide_request_action FOREIGN KEY (action_id) REFERENCES comment_moderation_action(id),
    CONSTRAINT chk_comment_hide_request_result CHECK (
        (action_id IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR
        (action_id IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
