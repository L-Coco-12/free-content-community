package com.freecontent.community.content.workspace.mapper;

import java.time.LocalDateTime;
import com.freecontent.community.content.model.persistence.RevisionRow;
import com.freecontent.community.content.model.persistence.ContentRestorationAudit;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentRestorationMapper {
    @Select("SELECT author_id FROM community_content WHERE id=#{content}")
    String findAuthor(@Param("content") String content);

    @Select("SELECT id FROM content_review WHERE pending_content_id=#{content} FOR UPDATE")
    String lockPendingId(@Param("content") String content);

    @Select("SELECT id,content_id,revision_number,status,title,body_markdown,created_at,updated_at FROM content_revision WHERE working_content_id=#{content} FOR UPDATE")
    RevisionRow lockWorking(@Param("content") String content);

    @Update("""
        UPDATE community_content SET lifecycle='ACTIVE',
            publication_status=CASE WHEN publication_status='TAKEN_DOWN' THEN 'TAKEN_DOWN'
                WHEN published_revision_id IS NULL THEN 'UNPUBLISHED' ELSE 'WITHDRAWN' END,
            deleted_at=NULL,version=version+1,updated_at=#{now}
        WHERE id=#{content} AND author_id=#{actor} AND lifecycle='DELETED' AND version=#{version} AND version<2147483647
        """)
    int restore(@Param("content") String content, @Param("actor") String actor, @Param("version") int version, @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO content_restoration_audit(id,actor_id,content_id,published_revision_id,before_publication_status,after_publication_status,
            before_content_version,after_content_version,request_id,occurred_at)
        VALUES(#{a.id},#{a.actorId},#{a.contentId},#{a.publishedRevisionId},#{a.beforePublicationStatus},#{a.afterPublicationStatus},
            #{a.beforeContentVersion},#{a.afterContentVersion},#{a.requestId},#{a.occurredAt})
        """)
    int insertAudit(@Param("a") ContentRestorationAudit audit);

    @Insert("""
        INSERT INTO content_lifecycle_outbox(id,content_id,published_revision_id,content_version,event_type,payload,state,created_at)
        VALUES(#{id},#{content},#{published},#{version},'CONTENT_RESTORED',#{payload},'PENDING',#{now})
        """)
    int insertEvent(@Param("id") String id, @Param("content") String content, @Param("published") String published,
            @Param("version") int version, @Param("payload") String payload, @Param("now") LocalDateTime now);
}
