-- CHECK 对 UNKNOWN 也放行，因此条件必填字段必须显式排除NULL。
-- V19已执行，补充新迁移，不回写旧迁移。
ALTER TABLE content_publication_withdrawal_audit DROP CHECK chk_publication_withdrawal_review;
ALTER TABLE content_publication_withdrawal_audit ADD CONSTRAINT chk_publication_withdrawal_review
    CHECK (
        (invalidated_review_id IS NULL AND cancelled_revision_id IS NULL AND before_review_version IS NULL AND after_review_version IS NULL)
        OR (invalidated_review_id IS NOT NULL AND cancelled_revision_id IS NOT NULL
            AND before_review_version IS NOT NULL AND after_review_version IS NOT NULL
            AND before_review_version>=1 AND after_review_version=before_review_version+1)
    );
