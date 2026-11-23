package com.freecontent.community.content.comment.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CommentHideMapper {
    @Select("SELECT state, version FROM content_comment WHERE id=#{id} FOR UPDATE")
    Target lockTarget(@Param("id") String commentId);

    @Update("""
            UPDATE content_comment SET state='HIDDEN', version=version+1, updated_at=#{now}
            WHERE id=#{id} AND state='VISIBLE' AND version=#{version}
            """)
    int hide(@Param("id") String commentId, @Param("version") int expectedVersion, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO comment_moderation_action(id, comment_id, operator_id, reason, before_state, after_state,
                                                  before_version, resulting_version, request_id, created_at)
            VALUES(#{a.id}, #{a.commentId}, #{a.operatorId}, #{a.reason}, 'VISIBLE', 'HIDDEN',
                   #{a.beforeVersion}, #{a.resultingVersion}, #{a.requestId}, #{a.createdAt})
            """)
    int insertAction(@Param("a") Action action);

    @Select("""
            SELECT id, comment_id, operator_id, reason, before_version, resulting_version, request_id, created_at
            FROM comment_moderation_action WHERE id=#{id}
            """)
    Action findAction(@Param("id") String id);

    record Target(String state, int version) { }
    record Action(String id, String commentId, String operatorId, String reason, int beforeVersion,
                  int resultingVersion, String requestId, LocalDateTime createdAt) { }
}
