CREATE TABLE content_publication_republish_audit (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    before_content_version INT NOT NULL,
    after_content_version INT NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_publication_republish_version (content_id, after_content_version),
    KEY idx_publication_republish_actor (actor_id, occurred_at, id),
    CONSTRAINT fk_publication_republish_actor FOREIGN KEY (actor_id) REFERENCES app_user(id),
    CONSTRAINT fk_publication_republish_content FOREIGN KEY (content_id) REFERENCES community_content(id),
    CONSTRAINT fk_publication_republish_revision FOREIGN KEY (published_revision_id,content_id) REFERENCES content_revision(id,content_id),
    CONSTRAINT chk_publication_republish_version CHECK (before_content_version>=1 AND after_content_version=before_content_version+1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE content_publication_outbox DROP CHECK chk_publication_outbox_type;
ALTER TABLE content_publication_outbox ADD CONSTRAINT chk_publication_outbox_type
    CHECK (event_type IN ('CONTENT_WITHDRAWN','CONTENT_REPUBLISHED'));
