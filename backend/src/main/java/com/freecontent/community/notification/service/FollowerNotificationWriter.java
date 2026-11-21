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
public class FollowerNotificationWriter {
    private final NotificationWriteMapper notifications;

    public FollowerNotificationWriter(NotificationWriteMapper notifications) {
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void create(String recipientId, String followId, String displayName, LocalDateTime now) {
        String id = UUID.randomUUID().toString().replace("-", "");
        if (notifications.insertNewFollower(id, recipientId, followId, displayName + "关注了你。", now) != 1) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "关注通知暂不可用");
        }
    }
}
