package com.freecontent.community.content.workspace.mapper;

import java.time.LocalDateTime;
import com.freecontent.community.content.model.persistence.PublicationRepublishAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContentRepublicationMapper {
    record PublicationTimes(LocalDateTime firstPublishedAt, LocalDateTime publishedAt) { }

    @Select("SELECT first_published_at,published_at FROM community_content WHERE id=#{content}")
    PublicationTimes publicationTimes(@Param("content") String content);

    @Select("SELECT id FROM content_revision WHERE content_id=#{content} AND status='APPROVED' ORDER BY revision_number DESC LIMIT 1 FOR UPDATE")
    String lockLatestApproved(@Param("content") String content);

    @Update("""
        UPDATE community_content SET publication_status='PUBLISHED',published_revision_id=#{revision},published_at=#{now},
            version=version+1,updated_at=#{now}
        WHERE id=#{content} AND author_id=#{actor} AND lifecycle='ACTIVE' AND publication_status='WITHDRAWN'
            AND version=#{version} AND version<2147483647
        """)
    int republish(@Param("content") String content, @Param("actor") String actor, @Param("version") int version,
            @Param("revision") String revision, @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO content_publication_republish_audit(id,actor_id,content_id,published_revision_id,
            before_content_version,after_content_version,request_id,occurred_at)
        VALUES(#{a.id},#{a.actorId},#{a.contentId},#{a.publishedRevisionId},#{a.beforeContentVersion},#{a.afterContentVersion},#{a.requestId},#{a.occurredAt})
        """)
    int insertAudit(@Param("a") PublicationRepublishAudit audit);

    @Insert("""
        INSERT INTO content_publication_outbox(id,content_id,published_revision_id,content_version,event_type,payload,state,created_at)
        VALUES(#{id},#{content},#{revision},#{version},'CONTENT_REPUBLISHED',#{payload},'PENDING',#{now})
        """)
    int insertEvent(@Param("id") String id, @Param("content") String content, @Param("revision") String revision,
            @Param("version") int version, @Param("payload") String payload, @Param("now") LocalDateTime now);
}
