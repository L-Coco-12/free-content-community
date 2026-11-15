package com.freecontent.community.content.workspace.service;

import java.util.UUID;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class PublicationWithdrawalAuditConstraintIT {
    @Autowired private JdbcTemplate jdbc;

    @Test
    @Transactional
    void auditCannotOmitReviewVersionsWhenItRecordsAnInvalidatedReview() {
        String actor = id(), content = id(), published = id(), revision = id(), review = id();
        jdbc.update("INSERT INTO app_user(id,email,email_normalized,password_hash,email_verified,email_verified_at,display_name,bio,account_status,version,auth_version,created_at,updated_at) "
                + "VALUES(?,?,?,'test-hash',true,UTC_TIMESTAMP(6),'审计约束测试','','ACTIVE',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", actor, actor + "@example.test", actor + "@example.test");
        jdbc.update("INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at) "
                + "VALUES(?,?,'ARTICLE','ACTIVE','UNPUBLISHED',8,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", content, actor);
        for (int number = 1; number <= 2; number++) jdbc.update("INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at) "
                + "VALUES(?,?,?,?,'测试','正文',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", number == 1 ? published : revision, content, number, number == 1 ? "APPROVED" : "CANCELLED");
        jdbc.update("INSERT INTO content_review(id,content_id,revision_id,purpose,state,version,submitted_at,decided_at) "
                + "VALUES(?,?,?,'UPDATE','INVALIDATED',4,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", review, content, revision);
        var error = assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO content_publication_withdrawal_audit "
                + "(id,actor_id,content_id,published_revision_id,invalidated_review_id,cancelled_revision_id,before_content_version,after_content_version,"
                + "before_review_version,after_review_version,request_id,occurred_at) VALUES(?,?,?,?,?,?,7,8,NULL,NULL,'req_constraint',UTC_TIMESTAMP(6))",
                id(), actor, content, published, review, revision));
        assertTrue(error.getCause() instanceof SQLException);
        var databaseError = (SQLException) error.getCause();
        assertEquals(3819, databaseError.getErrorCode());
        assertTrue(databaseError.getMessage().contains("chk_publication_withdrawal_review"));
    }

    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
