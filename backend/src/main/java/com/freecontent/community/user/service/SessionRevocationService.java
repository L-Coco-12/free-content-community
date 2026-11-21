package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;

public interface SessionRevocationService {
    void revoke(String sessionId,SessionPrincipal actor,String sourceIp);
}
