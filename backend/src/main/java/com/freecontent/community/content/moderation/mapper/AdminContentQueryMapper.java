package com.freecontent.community.content.moderation.mapper;

import com.freecontent.community.content.model.persistence.ContentRow;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AdminContentQueryMapper {
    String FILTER = """
        FROM community_content
        <where>
            <if test="author != null"> AND author_id=#{author} </if>
            <if test="lifecycle != null"> AND lifecycle=#{lifecycle} </if>
            <if test="publication != null"> AND publication_status=#{publication} </if>
        </where>
        """;
    @Select("<script> SELECT COUNT(*) " + FILTER + " </script>")
    long count(@Param("author") String author, @Param("lifecycle") String lifecycle, @Param("publication") String publication);
    @Select("""
        <script> SELECT id,author_id,kind,lifecycle,publication_status,version,working_revision_id,published_revision_id,
            active_review_id,created_at,updated_at,deleted_at
        """ + FILTER + " ORDER BY updated_at DESC,id DESC LIMIT #{limit} OFFSET #{offset} </script>")
    List<ContentRow> page(@Param("author") String author, @Param("lifecycle") String lifecycle, @Param("publication") String publication,
            @Param("offset") long offset, @Param("limit") int limit);
}
