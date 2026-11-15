CREATE TABLE content_publication_withdrawal_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    invalidated_review_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cancelled_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    before_content_version INT NOT NULL,
    after_content_version INT NOT NULL,
    before_review_version INT NULL,
    after_review_version INT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_publication_withdrawal_version (content_id, after_content_version),
    KEY idx_publication_withdrawal_actor (actor_id, occurred_at, id),
    CONSTRAINT fk_publication_withdrawal_actor FOREIGN KEY (actor_id) REFERENCES app_user(id),
    CONSTRAINT fk_publication_withdrawal_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_publication_withdrawal_published FOREIGN KEY (published_revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT fk_publication_withdrawal_review FOREIGN KEY (invalidated_review_id,content_id) REFERENCES content_review(id,content_id),
    CONSTRAINT fk_publication_withdrawal_cancelled FOREIGN KEY (cancelled_revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT chk_publication_withdrawal_content_version CHECK (before_content_version>=1 AND after_content_version=before_content_version+1),
    CONSTRAINT chk_publication_withdrawal_review CHECK (
        (invalidated_review_id IS NULL AND cancelled_revision_id IS NULL AND before_review_version IS NULL AND after_review_version IS NULL)
        OR (invalidated_review_id IS NOT NULL AND cancelled_revision_id IS NOT NULL AND before_review_version>=1 AND after_review_version=before_review_version+1)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE content_publication_outbox (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_version INT NOT NULL,
    event_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload JSON NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_publication_outbox_event (content_id,content_version,event_type),
    KEY idx_publication_outbox_pending (state,created_at,id),
    CONSTRAINT fk_publication_outbox_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_publication_outbox_revision FOREIGN KEY (published_revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT chk_publication_outbox_version CHECK (content_version>=1),
    CONSTRAINT chk_publication_outbox_type CHECK (event_type='CONTENT_WITHDRAWN'),
    CONSTRAINT chk_publication_outbox_state CHECK (state IN ('PENDING','PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE content_review_outbox DROP CHECK chk_review_outbox_type;
ALTER TABLE content_review_outbox ADD CONSTRAINT chk_review_outbox_type
    CHECK (event_type IN ('CONTENT_REVIEW_SUBMITTED','CONTENT_REVIEW_DECIDED','CONTENT_PUBLISHED','CONTENT_REVIEW_CANCELLED','CONTENT_REVIEW_INVALIDATED'));
