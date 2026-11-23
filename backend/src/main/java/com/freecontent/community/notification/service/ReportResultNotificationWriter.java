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
public class ReportResultNotificationWriter {
    private final NotificationWriteMapper notifications;

    public ReportResultNotificationWriter(NotificationWriteMapper notifications) {
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void create(String recipientId, String reportId, boolean resolved, LocalDateTime now) {
        String id = UUID.randomUUID().toString().replace("-", "");
        String body = resolved ? "你提交的举报已采纳。" : "你提交的举报已驳回。";
        if (notifications.insertReportResult(id, recipientId, reportId, body, now) != 1) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "举报结果通知暂不可用");
        }
    }
}
