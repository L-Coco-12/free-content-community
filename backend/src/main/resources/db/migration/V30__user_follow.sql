CREATE TABLE user_follow (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    follower_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    followed_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_follow_pair (follower_id, followed_id),
    KEY idx_user_follow_target (followed_id),
    CONSTRAINT fk_user_follow_follower FOREIGN KEY (follower_id) REFERENCES app_user(id),
    CONSTRAINT fk_user_follow_target FOREIGN KEY (followed_id) REFERENCES app_user(id),
    CONSTRAINT chk_user_follow_distinct CHECK (follower_id <> followed_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
