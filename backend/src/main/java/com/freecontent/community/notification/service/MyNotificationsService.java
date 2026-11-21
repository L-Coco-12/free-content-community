package com.freecontent.community.notification.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.notification.dto.NotificationPage;
import com.freecontent.community.notification.dto.UnreadCount;

public interface MyNotificationsService {
    NotificationPage list(String cursor, String limit, String readState, SessionPrincipal principal, String sourceIp);

    UnreadCount unreadCount(SessionPrincipal principal, String sourceIp);
}
