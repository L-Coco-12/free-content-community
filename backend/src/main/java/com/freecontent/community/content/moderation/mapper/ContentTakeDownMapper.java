package com.freecontent.community.content.moderation.mapper;

import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.content.model.persistence.ReviewRow;
import com.freecontent.community.content.model.persistence.RevisionRow;
import com.freecontent.community.content.model.persistence.ContentModerationActionRow;
import com.freecontent.community.content.model.persistence.TakeDownAudit;
import com.freecontent.community.content.model.persistence.ReviewEventReference;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentTakeDownMapper {
    @Select("SELECT author_id FROM community_content WHERE id=#{content}")
    String findAuthor(@Param("content") String content);
    @Select("SELECT id,author_id,kind,lifecycle,publication_status,version,working_revision_id,published_revision_id,active_review_id,created_at,updated_at,deleted_at FROM community_content WHERE id=#{content} FOR UPDATE")
    ContentRow lockContent(@Param("content") String content);
    @Select("SELECT id,content_id,revision_id,purpose,state,version,submitted_at,decided_at,reviewer_id,reason FROM content_review WHERE pending_content_id=#{content} FOR UPDATE")
    ReviewRow lockPending(@Param("content") String content);
    @Select("SELECT id,content_id,revision_number,status,title,body_markdown,created_at,updated_at FROM content_revision WHERE working_content_id=#{content} FOR UPDATE")
    RevisionRow lockWorking(@Param("content") String content);
    @Update("UPDATE content_revision SET status='CANCELLED',updated_at=#{now} WHERE id=#{revision} AND content_id=#{content} AND status IN ('DRAFT','IN_REVIEW')")
    int cancelWorking(@Param("content") String content,@Param("revision") String revision,@Param("now") LocalDateTime now);
    @Update("""
        UPDATE content_review SET state='INVALIDATED',version=version+1,decided_at=#{now},reviewer_id=NULL,reason=NULL
        WHERE id=#{review} AND content_id=#{content} AND revision_id=#{revision} AND state='PENDING' AND version=#{version} AND version<2147483647
        """)
    int invalidateReview(@Param("review") String review,@Param("content") String content,@Param("revision") String revision,@Param("version") int version,@Param("now") LocalDateTime now);
    @Update("""
        UPDATE community_content SET publication_status='TAKEN_DOWN',working_revision_id=NULL,active_review_id=NULL,version=version+1,updated_at=#{now}
        WHERE id=#{content} AND version=#{version} AND version<2147483647 AND publication_status IN ('PUBLISHED','WITHDRAWN')
        """)
    int takeDown(@Param("content") String content, @Param("version") int version, @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO content_moderation_action(id,content_id,reason_code,reason,operator_id,resulting_version,created_at)
        VALUES(#{a.id},#{a.contentId},#{a.reasonCode},#{a.reason},#{a.operatorId},#{a.resultingVersion},#{a.createdAt})
        """)
    int insertAction(@Param("a") ContentModerationActionRow action);
    @Select("SELECT id,content_id,reason_code,reason,operator_id,resulting_version,created_at FROM content_moderation_action WHERE id=#{action}")
    ContentModerationActionRow findAction(@Param("action") String action);
    @Insert("""
        INSERT INTO content_take_down_audit(id,action_id,content_id,operator_id,published_revision_id,cancelled_revision_id,before_working_status,
            invalidated_review_id,before_content_version,after_content_version,before_review_version,after_review_version,request_id,occurred_at)
        VALUES(#{a.id},#{a.actionId},#{a.contentId},#{a.operatorId},#{a.publishedRevisionId},#{a.cancelledRevisionId},#{a.beforeWorkingStatus},
            #{a.invalidatedReviewId},#{a.beforeContentVersion},#{a.afterContentVersion},#{a.beforeReviewVersion},#{a.afterReviewVersion},#{a.requestId},#{a.occurredAt})
        """)
    int insertAudit(@Param("a") TakeDownAudit audit);
    @Insert("""
        INSERT INTO content_publication_outbox(id,content_id,published_revision_id,content_version,event_type,payload,state,created_at)
        VALUES(#{id},#{content},#{published},#{version},'CONTENT_TAKEN_DOWN',#{payload},'PENDING',#{now})
        """)
    int insertPublicationEvent(@Param("id") String id,@Param("content") String content,@Param("published") String published,
            @Param("version") int version,@Param("payload") String payload,@Param("now") LocalDateTime now);
    @Insert("""
        INSERT INTO content_review_outbox(id,content_id,revision_id,review_id,content_version,event_type,payload,state,created_at)
        VALUES(#{id},#{ref.contentId},#{ref.revisionId},#{ref.id},#{version},'CONTENT_REVIEW_INVALIDATED',#{payload},'PENDING',#{now})
        """)
    int insertReviewEvent(@Param("id") String id,@Param("ref") ReviewEventReference ref,@Param("version") int version,@Param("payload") String payload,@Param("now") LocalDateTime now);
}
