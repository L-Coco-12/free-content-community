package com.freecontent.community.content.workspace.mapper;

import com.freecontent.community.content.model.persistence.DraftRevisionInsert;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContentDraftCreationMapper {
    @Select("SELECT COUNT(*) FROM content_revision WHERE working_content_id=#{content}")
    int workingCount(@Param("content") String content);

    @Select("SELECT MAX(revision_number) FROM content_revision WHERE content_id=#{content}")
    Integer maximumNumber(@Param("content") String content);

    @Select("SELECT id FROM content_revision WHERE content_id=#{content} ORDER BY revision_number DESC LIMIT 1")
    String latestRevisionId(@Param("content") String content);

    @Select("SELECT author_id FROM community_content WHERE id=#{content}")
    String findAuthor(@Param("content") String content);

    @Insert("""
        INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at)
        VALUES(#{draft.id},#{draft.contentId},#{draft.revisionNumber},'DRAFT',#{draft.title},#{draft.bodyMarkdown},#{now},#{now})
        """)
    int insertRevision(@Param("draft") DraftRevisionInsert draft, @Param("now") LocalDateTime now);

    @Insert("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) "
            + "SELECT #{target},asset_id,usage_type,position FROM content_revision_asset WHERE revision_id=#{source}")
    int copyAssets(@Param("source") String source, @Param("target") String target);

    @Insert("INSERT INTO content_revision_tag(revision_id,tag_id,position) "
            + "SELECT #{target},tag_id,position FROM content_revision_tag WHERE revision_id=#{source}")
    int copyTags(@Param("source") String source, @Param("target") String target);

    @Update("""
        UPDATE community_content SET working_revision_id=#{revision},version=version+1,updated_at=#{now}
        WHERE id=#{content} AND author_id=#{actor} AND version=#{version} AND version<2147483647
          AND lifecycle='ACTIVE' AND working_revision_id IS NULL AND active_review_id IS NULL
        """)
    int attachDraft(@Param("content") String content, @Param("actor") String actor, @Param("version") int version,
            @Param("revision") String revision, @Param("now") LocalDateTime now);
}
