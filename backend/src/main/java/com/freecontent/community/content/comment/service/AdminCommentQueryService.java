package com.freecontent.community.content.comment.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.comment.dto.AdminComment;
import com.freecontent.community.content.comment.dto.AdminCommentPage;

public interface AdminCommentQueryService {
    AdminComment get(String commentId, SessionPrincipal actor, String sourceIp);

    AdminCommentPage list(String page, String pageSize, String contentId, String authorId, String state,
                          SessionPrincipal actor, String sourceIp);
}
