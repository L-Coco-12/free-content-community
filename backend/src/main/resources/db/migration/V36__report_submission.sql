CREATE TABLE user_report (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reporter_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    comment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    description VARCHAR(1000) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    resolution VARCHAR(1000) NULL,
    open_target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN state='OPEN' THEN COALESCE(content_id, comment_id) END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_open_target (reporter_id, target_type, open_target_id),
    KEY idx_report_reporter_created (reporter_id, created_at, id),
    CONSTRAINT fk_report_reporter FOREIGN KEY (reporter_id) REFERENCES app_user(id),
    CONSTRAINT fk_report_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_report_comment FOREIGN KEY (comment_id) REFERENCES content_comment(id),
    CONSTRAINT chk_report_target CHECK (
        (target_type='CONTENT' AND content_id IS NOT NULL AND comment_id IS NULL) OR
        (target_type='COMMENT' AND comment_id IS NOT NULL AND content_id IS NULL)
    ),
    CONSTRAINT chk_report_reason CHECK (reason_code IN ('SPAM','ABUSE','COPYRIGHT','OTHER')),
    CONSTRAINT chk_report_resolution CHECK (
        (state='OPEN' AND resolved_at IS NULL AND resolution IS NULL) OR
        (state IN ('RESOLVED','DISMISSED') AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT chk_report_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE report_attachment (
    report_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position TINYINT UNSIGNED NOT NULL,
    PRIMARY KEY (report_id, position),
    UNIQUE KEY uk_report_attachment_asset (report_id, asset_id),
    CONSTRAINT fk_report_attachment_report FOREIGN KEY (report_id) REFERENCES user_report(id),
    CONSTRAINT fk_report_attachment_asset FOREIGN KEY (asset_id) REFERENCES media_asset(id),
    CONSTRAINT chk_report_attachment_position CHECK (position < 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE report_submission_request (
    reporter_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reporter_id, key_hash),
    KEY idx_report_submission_expiry (expires_at),
    CONSTRAINT fk_report_submission_reporter FOREIGN KEY (reporter_id) REFERENCES app_user(id),
    CONSTRAINT fk_report_submission_result FOREIGN KEY (report_id) REFERENCES user_report(id),
    CONSTRAINT chk_report_submission_result CHECK (
        (report_id IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR
        (report_id IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
