package com.freecontent.community.content.interaction.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentLikeMapper {
    @Select("SELECT id FROM community_content WHERE id=#{content} FOR SHARE")
    String lockTarget(@Param("content")String content);
    // 唯一键仅为用户与作品这一对，重复键异常明确表示该关系已存在；不使用INSERT IGNORE吞掉其他错误。
    @Insert("INSERT INTO content_like(user_id,content_id,created_at) VALUES(#{user},#{content},#{now})")
    int insert(@Param("user")String user,@Param("content")String content,@Param("now")LocalDateTime now);
    @Insert("INSERT INTO content_like_stat(content_id,like_count,created_at,updated_at) VALUES(#{content},1,#{now},#{now}) ON DUPLICATE KEY UPDATE like_count=like_count+1,updated_at=#{now}")
    int increment(@Param("content")String content,@Param("now")LocalDateTime now);
    @Delete("DELETE FROM content_like WHERE user_id=#{user} AND content_id=#{content}")
    int delete(@Param("user")String user,@Param("content")String content);
    @Update("UPDATE content_like_stat SET like_count=like_count-1,updated_at=#{now} WHERE content_id=#{content} AND like_count>0")
    int decrement(@Param("content")String content,@Param("now")LocalDateTime now);
}
