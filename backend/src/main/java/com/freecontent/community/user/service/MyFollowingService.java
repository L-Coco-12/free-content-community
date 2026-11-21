package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.user.dto.UserPage;

public interface MyFollowingService {
    UserPage list(String cursor, String limit, SessionPrincipal actor, String sourceIp);
}
