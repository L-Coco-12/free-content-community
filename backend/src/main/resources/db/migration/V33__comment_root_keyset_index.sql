CREATE INDEX idx_comment_root_seek
    ON content_comment (content_id, root_comment_id, created_at DESC, id DESC, state);
