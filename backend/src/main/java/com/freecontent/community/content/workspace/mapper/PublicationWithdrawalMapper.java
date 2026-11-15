package com.freecontent.community.content.workspace.mapper;

import com.freecontent.community.content.model.persistence.ReviewRow;
import com.freecontent.community.content.model.persistence.RevisionRow;
import com.freecontent.community.content.model.persistence.PublicationWithdrawalAudit;
import com.freecontent.community.content.model.persistence.ReviewEventReference;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PublicationWithdrawalMapper {
    @Select("SELECT id,content_id,revision_id,purpose,state,version,submitted_at,decided_at,reviewer_id,reason "
            + "FROM content_review WHERE pending_content_id=#{content} FOR UPDATE")
    ReviewRow lockPending(@Param("content") String content);

    @Select("SELECT id,content_id,revision_number,status,title,body_markdown,created_at,updated_at "
            + "FROM content_revision WHERE working_content_id=#{content} FOR UPDATE")
    RevisionRow lockWorking(@Param("content") String content);

    @Update("UPDATE content_revision SET status='CANCELLED',updated_at=#{now} WHERE id=#{revision} AND content_id=#{content} AND status='IN_REVIEW'")
    int cancelRevision(@Param("content") String content, @Param("revision") String revision, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE content_review SET state='INVALIDATED',version=version+1,decided_at=#{now},reviewer_id=NULL,reason=NULL
        WHERE id=#{review} AND content_id=#{content} AND revision_id=#{revision} AND state='PENDING'
          AND version=#{version} AND version<2147483647
        """)
    int invalidateReview(@Param("review") String review, @Param("content") String content, @Param("revision") String revision,
            @Param("version") int version, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE community_content SET publication_status='WITHDRAWN',version=version+1,updated_at=#{now},
            working_revision_id=#{working},active_review_id=NULL
        WHERE id=#{content} AND author_id=#{actor} AND lifecycle='ACTIVE' AND publication_status='PUBLISHED'
          AND version=#{version} AND version<2147483647
        """)
    int withdraw(@Param("content") String content, @Param("actor") String actor, @Param("version") int version,
            @Param("working") String working, @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO content_publication_withdrawal_audit(id,actor_id,content_id,published_revision_id,invalidated_review_id,cancelled_revision_id,
            before_content_version,after_content_version,before_review_version,after_review_version,request_id,occurred_at)
        VALUES(#{a.id},#{a.actorId},#{a.contentId},#{a.publishedRevisionId},#{a.invalidatedReviewId},#{a.cancelledRevisionId},
            #{a.beforeContentVersion},#{a.afterContentVersion},#{a.beforeReviewVersion},#{a.afterReviewVersion},#{a.requestId},#{a.occurredAt})
        """)
    int insertAudit(@Param("a") PublicationWithdrawalAudit audit);

    @Insert("""
        INSERT INTO content_publication_outbox(id,content_id,published_revision_id,content_version,event_type,payload,state,created_at)
        VALUES(#{id},#{content},#{published},#{version},'CONTENT_WITHDRAWN',#{payload},'PENDING',#{now})
        """)
    int insertPublicationEvent(@Param("id") String id, @Param("content") String content, @Param("published") String published,
            @Param("version") int version, @Param("payload") String payload, @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO content_review_outbox(id,content_id,revision_id,review_id,content_version,event_type,payload,state,created_at)
        VALUES(#{id},#{ref.contentId},#{ref.revisionId},#{ref.id},#{version},'CONTENT_REVIEW_INVALIDATED',#{payload},'PENDING',#{now})
        """)
    int insertReviewEvent(@Param("id") String id, @Param("ref") ReviewEventReference ref, @Param("version") int version,
            @Param("payload") String payload, @Param("now") LocalDateTime now);
}
