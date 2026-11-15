package com.freecontent.community.content.moderation.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.WorkspacePage;
import com.freecontent.community.content.dto.ContentWorkspace;

public interface AdminContentQueryService {
    WorkspacePage list(String page, String pageSize, String authorId, String lifecycle, String publicationStatus, SessionPrincipal principal, String sourceIp);
    ContentWorkspace get(String contentId, SessionPrincipal principal, String sourceIp);
}
