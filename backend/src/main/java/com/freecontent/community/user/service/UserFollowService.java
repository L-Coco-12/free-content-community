package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;

public interface UserFollowService {
    void follow(String userId, SessionPrincipal actor, String sourceIp);
    void unfollow(String userId, SessionPrincipal actor, String sourceIp);
}
