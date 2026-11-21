package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.dto.RevisionView;
import com.freecontent.community.content.dto.WorkspacePage;
import com.freecontent.community.content.workspace.dto.RevisionPage;

public interface ContentWorkspaceService {
    ContentWorkspace getMyWorkspace(String contentId, SessionPrincipal principal, String sourceIp);
    RevisionView getMyRevision(String contentId, String revisionId, SessionPrincipal principal, String sourceIp);
    RevisionPage listMyRevisions(String contentId,String page,String pageSize,SessionPrincipal principal,String sourceIp);
    WorkspacePage listMyContents(String page,String pageSize,String lifecycle,String publicationStatus,String kind,
            SessionPrincipal principal,String sourceIp);
}
