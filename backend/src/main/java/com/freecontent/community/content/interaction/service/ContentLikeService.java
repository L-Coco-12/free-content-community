package com.freecontent.community.content.interaction.service;

import com.freecontent.community.auth.security.SessionPrincipal;

public interface ContentLikeService {
    void like(String contentId,SessionPrincipal actor,String sourceIp);
    void unlike(String contentId,SessionPrincipal actor,String sourceIp);
}
