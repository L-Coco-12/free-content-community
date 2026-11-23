package com.freecontent.community.content.comment.service;

import com.freecontent.community.auth.security.SessionPrincipal;

public interface CommentDeletionService {
    void delete(String commentId, SessionPrincipal actor, String sourceIp);
}
