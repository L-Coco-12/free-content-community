CREATE TABLE content_moderation_action (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resulting_version INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_moderation_identity (id,content_id),
    UNIQUE KEY uk_content_moderation_version (content_id,resulting_version),
    KEY idx_content_moderation_operator (operator_id,created_at,id),
    CONSTRAINT fk_content_moderation_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_content_moderation_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    CONSTRAINT chk_content_moderation_reason_code CHECK (reason_code IN ('SPAM','ABUSE','COPYRIGHT','OTHER')),
    CONSTRAINT chk_content_moderation_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 1000),
    CONSTRAINT chk_content_moderation_version CHECK (resulting_version>=2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE content_take_down_request (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    action_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_snapshot JSON NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_take_down_request_scope (created_by,key_hash),
    KEY idx_take_down_request_expiry (expires_at),
    CONSTRAINT fk_take_down_request_actor FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_take_down_request_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_take_down_request_action FOREIGN KEY (action_id,content_id) REFERENCES content_moderation_action(id,content_id),
    CONSTRAINT chk_take_down_request_state CHECK (state IN ('PROCESSING','SUCCEEDED')),
    CONSTRAINT chk_take_down_request_snapshot CHECK (
        (state='PROCESSING' AND content_id IS NULL AND action_id IS NULL AND result_snapshot IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state='SUCCEEDED' AND content_id IS NOT NULL AND action_id IS NOT NULL AND result_snapshot IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE content_take_down_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cancelled_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    before_working_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    invalidated_review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    before_content_version INT NOT NULL,
    after_content_version INT NOT NULL,
    before_review_version INT NULL,
    after_review_version INT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_take_down_audit_version (content_id,after_content_version),
    UNIQUE KEY uk_take_down_audit_action (action_id),
    CONSTRAINT fk_take_down_audit_action FOREIGN KEY (action_id,content_id) REFERENCES content_moderation_action(id,content_id),
    CONSTRAINT fk_take_down_audit_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    CONSTRAINT fk_take_down_audit_published FOREIGN KEY (published_revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT fk_take_down_audit_cancelled FOREIGN KEY (cancelled_revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT fk_take_down_audit_review FOREIGN KEY (invalidated_review_id,content_id) REFERENCES content_review(id,content_id),
    CONSTRAINT chk_take_down_audit_content_version CHECK (before_content_version>=1 AND after_content_version=before_content_version+1),
    CONSTRAINT chk_take_down_audit_working CHECK (
        (cancelled_revision_id IS NULL AND before_working_status IS NULL)
        OR (cancelled_revision_id IS NOT NULL AND before_working_status IS NOT NULL AND before_working_status IN ('DRAFT','IN_REVIEW'))
    ),
    CONSTRAINT chk_take_down_audit_review CHECK (
        (invalidated_review_id IS NULL AND before_review_version IS NULL AND after_review_version IS NULL)
        OR (invalidated_review_id IS NOT NULL AND cancelled_revision_id IS NOT NULL AND before_working_status IS NOT NULL
            AND before_working_status='IN_REVIEW' AND before_review_version IS NOT NULL AND after_review_version IS NOT NULL
            AND before_review_version>=1 AND after_review_version=before_review_version+1)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_notification (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recipient_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(120) NOT NULL,
    body VARCHAR(500) NOT NULL,
    target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_event (recipient_id,source_event_id,type),
    KEY idx_notification_recipient_created (recipient_id,created_at,id),
    KEY idx_notification_unread (recipient_id,read_at),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id) REFERENCES app_user(id),
    CONSTRAINT chk_notification_type CHECK (type IN ('COMMENT_REPLY','NEW_FOLLOWER','REVIEW_RESULT','MODERATION_RESULT','REPORT_RESULT','MEDIA_RESULT')),
    CONSTRAINT chk_notification_target CHECK ((target_type IS NULL AND target_id IS NULL)
        OR (target_type IS NOT NULL AND target_id IS NOT NULL AND target_type IN ('CONTENT','COMMENT','REVIEW','REPORT','TASK')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE content_publication_outbox DROP CHECK chk_publication_outbox_type;
ALTER TABLE content_publication_outbox ADD CONSTRAINT chk_publication_outbox_type
    CHECK (event_type IN ('CONTENT_WITHDRAWN','CONTENT_REPUBLISHED','CONTENT_TAKEN_DOWN'));
