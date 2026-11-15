package com.freecontent.community.content.interaction.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentBookmarkMapper {
    @Insert("INSERT INTO content_bookmark(id,user_id,content_id,created_at) VALUES(#{id},#{user},#{content},#{now})")
    int insert(@Param("id")String id,@Param("user")String user,@Param("content")String content,@Param("now")LocalDateTime now);
    @Select("SELECT id FROM content_bookmark WHERE user_id=#{user} AND content_id=#{content} FOR SHARE")
    String findExisting(@Param("user")String user,@Param("content")String content);
}
