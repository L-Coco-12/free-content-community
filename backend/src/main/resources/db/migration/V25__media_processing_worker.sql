ALTER TABLE media_processing_task
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD COLUMN available_at DATETIME(6) NULL,
    ADD KEY idx_media_processing_due (state,available_at,lease_until),
    ADD CONSTRAINT chk_media_processing_attempt CHECK (attempt_count >= 0);

-- 每次执行先登记独立结果对象键，再执行存储写入；崩溃后仍可定位孤立对象。
CREATE TABLE media_processing_output (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    available_at DATETIME(6) NOT NULL,
    lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_processing_output_key (object_key),
    KEY idx_media_processing_output_due (state,available_at),
    CONSTRAINT fk_media_processing_output_task FOREIGN KEY (task_id) REFERENCES media_processing_task(id) ON DELETE CASCADE,
    CONSTRAINT chk_media_processing_output_state CHECK (state IN ('RESERVED','BOUND','CLEANING','CLEANED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
