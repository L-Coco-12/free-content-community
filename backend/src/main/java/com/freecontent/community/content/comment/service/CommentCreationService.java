package com.freecontent.community.content.comment.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.comment.dto.CommentView;
import com.freecontent.community.content.comment.dto.CreateCommentRequest;

public interface CommentCreationService {
    CommentView create(String contentId, CreateCommentRequest request, String key, SessionPrincipal actor, String sourceIp);
}
