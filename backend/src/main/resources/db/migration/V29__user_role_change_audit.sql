CREATE TABLE user_role_change_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    before_roles JSON NOT NULL,
    after_roles JSON NOT NULL,
    before_version INT NOT NULL,
    after_version INT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_user_role_change_audit_version (user_id, after_version),
    KEY idx_user_role_change_audit_operator (operator_id, occurred_at, id),
    CONSTRAINT fk_user_role_change_audit_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_user_role_change_audit_operator FOREIGN KEY (operator_id) REFERENCES app_user(id),
    CONSTRAINT chk_user_role_change_audit_roles CHECK (
        JSON_TYPE(before_roles) = 'ARRAY' AND JSON_TYPE(after_roles) = 'ARRAY'
        AND JSON_CONTAINS(after_roles, JSON_QUOTE('USER')) = 1
    ),
    CONSTRAINT chk_user_role_change_audit_version CHECK (before_version >= 1 AND after_version = before_version + 1),
    CONSTRAINT chk_user_role_change_audit_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
