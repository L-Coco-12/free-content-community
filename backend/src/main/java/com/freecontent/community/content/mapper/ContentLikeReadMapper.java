package com.freecontent.community.content.mapper;

import com.freecontent.community.content.model.persistence.ContentLikeState;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentLikeReadMapper {
    @Select("""
        <script>
        SELECT c.id AS content_id,COALESCE(s.like_count,0) AS like_count,
          CASE WHEN #{viewer,jdbcType=VARCHAR} IS NULL THEN FALSE ELSE EXISTS(
            SELECT 1 FROM content_like l WHERE l.user_id=#{viewer,jdbcType=VARCHAR} AND l.content_id=c.id
          ) END AS viewer_has_liked
        FROM community_content c LEFT JOIN content_like_stat s ON s.content_id=c.id
        WHERE c.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<ContentLikeState> find(@Param("ids")List<String> ids,@Param("viewer")String viewer);
}
