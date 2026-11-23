package com.freecontent.community.notification.service;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.notification.mapper.NotificationWriteMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CommentReplyNotificationWriter {
    private final NotificationWriteMapper notifications;

    public CommentReplyNotificationWriter(NotificationWriteMapper notifications) {
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void create(String recipientId, String replyId, LocalDateTime now) {
        String notificationId = UUID.randomUUID().toString().replace("-", "");
        if (notifications.insertCommentReply(notificationId, recipientId, replyId, now) != 1) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "评论回复通知暂不可用");
        }
    }
}
