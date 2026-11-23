package com.freecontent.community.content.comment.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.comment.dto.CommentPage;
import com.freecontent.community.content.comment.dto.CommentView;

public interface PublicCommentService {
    CommentView read(String commentId, SessionPrincipal viewer, String sourceIp);
    CommentPage list(String contentId, String cursor, String limit, SessionPrincipal viewer, String sourceIp);
    CommentPage listReplies(String rootCommentId, String cursor, String limit, SessionPrincipal viewer, String sourceIp);
}
