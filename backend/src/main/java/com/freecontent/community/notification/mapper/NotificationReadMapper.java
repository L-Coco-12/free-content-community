package com.freecontent.community.notification.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NotificationReadMapper {
    @Select("SELECT COUNT(*) FROM user_notification WHERE recipient_id=#{recipient} AND read_at IS NULL")
    long countUnread(@Param("recipient") String recipient);

    @Select("""
            <script>
            SELECT id,type,title,body,target_type,target_id,read_at,created_at
            FROM user_notification
            WHERE recipient_id=#{recipient} AND created_at &lt;= #{snapshot}
            <if test="readState == 'UNREAD'">AND read_at IS NULL</if>
            <if test="readState == 'READ'">AND read_at IS NOT NULL</if>
            <if test="lastAt != null">
                AND (created_at &lt; #{lastAt} OR (created_at=#{lastAt} AND id &lt; #{lastId}))
            </if>
            ORDER BY created_at DESC,id DESC
            LIMIT #{take}
            </script>
            """)
    List<Row> list(@Param("recipient") String recipient, @Param("readState") String readState,
                   @Param("snapshot") LocalDateTime snapshot, @Param("lastAt") LocalDateTime lastAt,
                   @Param("lastId") String lastId, @Param("take") int take);

    record Row(String id, String type, String title, String body, String targetType, String targetId,
               LocalDateTime readAt, LocalDateTime createdAt) { }
}
