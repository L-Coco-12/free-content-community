CREATE TABLE report_decision_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    content_action_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    comment_action_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    before_version INT NOT NULL,
    resulting_version INT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_decision_audit_report (report_id),
    KEY idx_report_decision_audit_operator (operator_id, occurred_at, id),
    CONSTRAINT fk_report_decision_audit_report FOREIGN KEY (report_id) REFERENCES user_report(id),
    CONSTRAINT fk_report_decision_audit_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    CONSTRAINT fk_report_decision_content_action FOREIGN KEY (content_action_id) REFERENCES content_moderation_action(id),
    CONSTRAINT fk_report_decision_comment_action FOREIGN KEY (comment_action_id) REFERENCES comment_moderation_action(id),
    CONSTRAINT chk_report_decision_outcome CHECK (decision IN ('RESOLVE','DISMISS')),
    CONSTRAINT chk_report_decision_action CHECK (
        (content_action_id IS NULL OR comment_action_id IS NULL)
        AND (decision='DISMISS' OR content_action_id IS NOT NULL OR comment_action_id IS NOT NULL)
    ),
    CONSTRAINT chk_report_decision_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 1000),
    CONSTRAINT chk_report_decision_version CHECK (before_version>=1 AND resulting_version=before_version+1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE report_decision_request (
    operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_snapshot JSON NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (operator_id, key_hash),
    KEY idx_report_decision_request_expiry (expires_at),
    CONSTRAINT fk_report_decision_request_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    CONSTRAINT fk_report_decision_request_result FOREIGN KEY (decision_id) REFERENCES report_decision_audit(id),
    CONSTRAINT chk_report_decision_request_result CHECK (
        (decision_id IS NULL AND result_snapshot IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR
        (decision_id IS NOT NULL AND result_snapshot IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
