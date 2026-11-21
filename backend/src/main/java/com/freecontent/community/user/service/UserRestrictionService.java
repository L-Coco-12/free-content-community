package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.RestrictionRequest;

public interface UserRestrictionService {
    AdminUser restrict(String userId, RestrictionRequest request, SessionPrincipal principal, String sourceIp, String requestId);
}
