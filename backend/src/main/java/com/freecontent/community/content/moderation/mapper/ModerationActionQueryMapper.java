package com.freecontent.community.content.moderation.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ModerationActionQueryMapper {
    @Select("""
            SELECT id, 'CONTENT' AS target_type, content_id AS target_id, 'TAKE_DOWN' AS action,
                   reason, operator_id, created_at, resulting_version
            FROM content_moderation_action WHERE id=#{id}
            UNION ALL
            SELECT id, 'COMMENT', comment_id, 'HIDE_COMMENT', reason, operator_id, created_at, resulting_version
            FROM comment_moderation_action WHERE id=#{id}
            """)
    List<ActionRow> findById(@Param("id") String actionId);

    record ActionRow(String id, String targetType, String targetId, String action, String reason,
                     String operatorId, LocalDateTime createdAt, int resultingVersion) { }
}
