ALTER TABLE user_report
    ADD INDEX idx_report_state_created (state, created_at, id);
