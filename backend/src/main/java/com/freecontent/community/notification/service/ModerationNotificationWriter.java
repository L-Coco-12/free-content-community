package com.freecontent.community.notification.service;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.notification.mapper.NotificationWriteMapper;
import com.freecontent.community.notification.model.ModerationNoticeInsert;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 仅在调用方业务事务内保存作者通知，不投递外部消息。 */
@Component
public class ModerationNotificationWriter {
    private final NotificationWriteMapper notifications;
    public ModerationNotificationWriter(NotificationWriteMapper notifications) { this.notifications = notifications; }
    @Transactional(propagation = Propagation.MANDATORY)
    public String create(String recipient,String content,String event,String reason,LocalDateTime now) {
        String body = "作品已被强制下架，请新建修订整改并重新提交审核。原因：" + reason;
        if (body.codePointCount(0,body.length()) > 500) body = body.substring(0,body.offsetByCodePoints(0,499)) + "…";
        String id = UUID.randomUUID().toString().replace("-","");
        if (notifications.insertModerationResult(new ModerationNoticeInsert(id,recipient,event,content,body,now)) != 1)
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"通知记录暂不可用");
        return id;
    }
}
