package com.freecontent.community.content.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ContentBookmarkReadMapper {
    @Select("""
        <script>SELECT content_id FROM content_bookmark WHERE user_id=#{viewer} AND content_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
        """)
    List<String> find(@Param("ids")List<String> ids,@Param("viewer")String viewer);
}
