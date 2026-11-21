package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.RoleUpdateRequest;

public interface UserRoleUpdateService {
    AdminUser replace(String userId, RoleUpdateRequest request, SessionPrincipal principal, String sourceIp, String requestId);
}
