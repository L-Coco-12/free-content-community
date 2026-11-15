package com.freecontent.community.notification.mapper;

import com.freecontent.community.notification.model.ModerationNoticeInsert;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NotificationWriteMapper {
    @Insert("""
        INSERT INTO user_notification(id,recipient_id,source_event_id,type,title,body,target_type,target_id,read_at,created_at)
        VALUES(#{n.id},#{n.recipientId},#{n.sourceEventId},'MODERATION_RESULT','作品已被强制下架',#{n.body},'CONTENT',#{n.contentId},NULL,#{n.createdAt})
        """)
    int insertModerationResult(@Param("n") ModerationNoticeInsert notice);
}
