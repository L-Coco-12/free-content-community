package com.freecontent.community.notification.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.notification.dto.BatchReadResult;
import com.freecontent.community.notification.dto.NotificationPage;
import com.freecontent.community.notification.dto.UnreadCount;
import java.util.List;

public interface MyNotificationsService {
    void markRead(String notificationId, SessionPrincipal principal, String sourceIp);

    BatchReadResult markBatchRead(List<String> notificationIds, SessionPrincipal principal, String sourceIp);

    NotificationPage list(String cursor, String limit, String readState, SessionPrincipal principal, String sourceIp);

    UnreadCount unreadCount(SessionPrincipal principal, String sourceIp);
}
