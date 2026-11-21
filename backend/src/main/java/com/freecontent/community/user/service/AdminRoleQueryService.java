package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.user.dto.RoleList;

public interface AdminRoleQueryService {
    RoleList list(SessionPrincipal principal, String sourceIp);
}
