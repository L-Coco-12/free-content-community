CREATE INDEX idx_user_follow_owner_seek
    ON user_follow (follower_id, created_at DESC, id DESC, followed_id);
