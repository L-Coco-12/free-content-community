CREATE TABLE content_deletion_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    publication_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    invalidated_review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cancelled_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    before_content_version INT NOT NULL,
    after_content_version INT NOT NULL,
    before_review_version INT NULL,
    after_review_version INT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_deletion_version (content_id, after_content_version),
    KEY idx_content_deletion_actor (actor_id, occurred_at, id),
    CONSTRAINT fk_content_deletion_actor FOREIGN KEY (actor_id) REFERENCES app_user(id),
    CONSTRAINT fk_content_deletion_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_content_deletion_published FOREIGN KEY (published_revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT fk_content_deletion_review FOREIGN KEY (invalidated_review_id,content_id) REFERENCES content_review(id,content_id),
    CONSTRAINT fk_content_deletion_cancelled FOREIGN KEY (cancelled_revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT chk_content_deletion_publication CHECK (publication_status IN ('UNPUBLISHED','PUBLISHED','WITHDRAWN','TAKEN_DOWN')),
    CONSTRAINT chk_content_deletion_version CHECK (before_content_version>=1 AND after_content_version=before_content_version+1),
    CONSTRAINT chk_content_deletion_review CHECK (
        (invalidated_review_id IS NULL AND cancelled_revision_id IS NULL AND before_review_version IS NULL AND after_review_version IS NULL)
        OR (invalidated_review_id IS NOT NULL AND cancelled_revision_id IS NOT NULL
            AND before_review_version IS NOT NULL AND after_review_version IS NOT NULL
            AND before_review_version>=1 AND after_review_version=before_review_version+1)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 未发布内容也可以删除，历史发布指针允许为NULL，不复用要求发布指针非空的发布Outbox。
CREATE TABLE content_lifecycle_outbox (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    content_version INT NOT NULL,
    event_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload JSON NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_lifecycle_outbox_event (content_id,content_version,event_type),
    KEY idx_content_lifecycle_outbox_pending (state,created_at,id),
    CONSTRAINT fk_content_lifecycle_outbox_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_content_lifecycle_outbox_revision FOREIGN KEY (published_revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT chk_content_lifecycle_outbox_version CHECK (content_version>=1),
    CONSTRAINT chk_content_lifecycle_outbox_type CHECK (event_type='CONTENT_DELETED'),
    CONSTRAINT chk_content_lifecycle_outbox_state CHECK (state IN ('PENDING','PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
