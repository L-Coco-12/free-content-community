CREATE TABLE content_bookmark (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_bookmark_pair (user_id,content_id),
    KEY idx_content_bookmark_owner_order (user_id,created_at DESC,id DESC),
    KEY idx_content_bookmark_target (content_id,user_id),
    CONSTRAINT fk_content_bookmark_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_content_bookmark_content FOREIGN KEY (content_id) REFERENCES community_content(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
