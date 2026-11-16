package com.freecontent.community.content.interaction.service;

import com.freecontent.community.auth.security.SessionPrincipal;

public interface ContentBookmarkService {
    void bookmark(String contentId,SessionPrincipal actor,String sourceIp);
    void unbookmark(String contentId,SessionPrincipal actor,String sourceIp);
}
