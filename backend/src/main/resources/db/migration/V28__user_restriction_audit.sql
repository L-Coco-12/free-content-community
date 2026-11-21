-- Serialize administrative changes that can remove an effective administrator.
CREATE TABLE admin_account_change_guard (
    id TINYINT NOT NULL PRIMARY KEY,
    CONSTRAINT chk_admin_account_change_guard_singleton CHECK (id = 1)
) ENGINE=InnoDB;

INSERT INTO admin_account_change_guard (id) VALUES (1);

CREATE TABLE user_restriction_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    before_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    after_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    before_version INT NOT NULL,
    after_version INT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_user_restriction_audit_version (user_id, after_version),
    KEY idx_user_restriction_audit_operator (operator_id, occurred_at, id),
    CONSTRAINT fk_user_restriction_audit_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_user_restriction_audit_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    CONSTRAINT chk_user_restriction_audit_status CHECK (
        before_status IN ('ACTIVE', 'WRITE_BLOCKED', 'DISABLED')
        AND after_status IN ('ACTIVE', 'WRITE_BLOCKED', 'DISABLED')
        AND before_status <> after_status
    ),
    CONSTRAINT chk_user_restriction_audit_version CHECK (before_version >= 1 AND after_version = before_version + 1),
    CONSTRAINT chk_user_restriction_audit_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
