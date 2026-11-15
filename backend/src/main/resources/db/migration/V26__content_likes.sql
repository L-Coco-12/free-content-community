CREATE TABLE content_like (
    user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id,content_id),
    KEY idx_content_like_target (content_id,user_id),
    CONSTRAINT fk_content_like_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_content_like_content FOREIGN KEY (content_id) REFERENCES community_content(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 按首次真实点赞惰性创建；已有作品没有统计行时读取为0，不修改编辑版本或updated_at。
CREATE TABLE content_like_stat (
    content_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    like_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (content_id),
    CONSTRAINT fk_content_like_stat_content FOREIGN KEY (content_id) REFERENCES community_content(id) ON DELETE CASCADE,
    CONSTRAINT chk_content_like_count CHECK (like_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
