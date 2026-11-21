package com.freecontent.community.notification.mapper;

import com.freecontent.community.notification.model.ModerationNoticeInsert;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NotificationWriteMapper {
    String OWNED_BATCH = """
            WHERE recipient_id=#{recipient} AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")"> #{id} </foreach>
            """;

    @Insert("""
        INSERT INTO user_notification(id,recipient_id,source_event_id,type,title,body,target_type,target_id,read_at,created_at)
        VALUES(#{n.id},#{n.recipientId},#{n.sourceEventId},'MODERATION_RESULT','作品已被强制下架',#{n.body},'CONTENT',#{n.contentId},NULL,#{n.createdAt})
        """)
    int insertModerationResult(@Param("n") ModerationNoticeInsert notice);

    @Insert("""
            INSERT INTO user_notification(id,recipient_id,source_event_id,type,title,body,target_type,target_id,read_at,created_at)
            VALUES(#{id},#{recipient},#{followId},'NEW_FOLLOWER','新的关注者',#{body},NULL,NULL,NULL,#{now})
            """)
    int insertNewFollower(@Param("id") String id, @Param("recipient") String recipient,
                          @Param("followId") String followId, @Param("body") String body,
                          @Param("now") LocalDateTime now);

    @Update("""
            UPDATE user_notification SET read_at=#{now}
            WHERE id=#{id} AND recipient_id=#{recipient} AND read_at IS NULL
            """)
    int markUnreadAsRead(@Param("id") String id, @Param("recipient") String recipient, @Param("now") LocalDateTime now);

    @Select("SELECT id FROM user_notification WHERE id=#{id} AND recipient_id=#{recipient} FOR UPDATE")
    String lockOwned(@Param("id") String id, @Param("recipient") String recipient);

    @Select("<script> SELECT id FROM user_notification FORCE INDEX (PRIMARY) " + OWNED_BATCH
            + " ORDER BY id FOR UPDATE </script>")
    List<String> lockOwnedBatch(@Param("ids") List<String> ids, @Param("recipient") String recipient);

    @Update("<script> UPDATE user_notification SET read_at=#{now} " + OWNED_BATCH + " AND read_at IS NULL </script>")
    int markUnreadBatchAsRead(@Param("ids") List<String> ids, @Param("recipient") String recipient,
                              @Param("now") LocalDateTime now);
}
