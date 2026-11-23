CREATE TABLE report_attachment_access_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_report_attachment_access_actor (actor_id, occurred_at, id),
    KEY idx_report_attachment_access_target (report_id, asset_id, occurred_at, id),
    CONSTRAINT fk_report_attachment_access_actor FOREIGN KEY (actor_id) REFERENCES app_user(id),
    CONSTRAINT fk_report_attachment_access_report FOREIGN KEY (report_id) REFERENCES user_report(id),
    CONSTRAINT fk_report_attachment_access_asset FOREIGN KEY (asset_id) REFERENCES media_asset(id),
    CONSTRAINT chk_report_attachment_access_action CHECK (action='ADMIN_REPORT_ATTACHMENT_ACCESS'),
    CONSTRAINT chk_report_attachment_access_expiry CHECK (expires_at>occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
