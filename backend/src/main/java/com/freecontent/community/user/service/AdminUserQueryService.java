package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.AdminUserPage;

public interface AdminUserQueryService {
    AdminUser get(String userId, SessionPrincipal principal, String sourceIp);

    AdminUserPage list(String page, String pageSize, String userId, String status,
                       SessionPrincipal principal, String sourceIp);
}
