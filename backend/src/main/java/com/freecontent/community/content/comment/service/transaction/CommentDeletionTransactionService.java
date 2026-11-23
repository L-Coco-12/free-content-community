package com.freecontent.community.content.comment.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.comment.mapper.CommentMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentDeletionTransactionService {
    private final CommentWriteAccess access;
    private final CommentMapper comments;
    private final Clock clock;

    public CommentDeletionTransactionService(CommentWriteAccess access, CommentMapper comments, Clock clock) {
        this.access = access;
        this.comments = comments;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(String commentId, SessionPrincipal principal) {
        String authorId = access.authorize(principal, false);
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        int changed = comments.markOwnedDeleted(commentId, authorId, now);
        if (changed == 1) return;
        if (changed == 0) {
            String state = comments.findOwnedState(commentId, authorId);
            if (state == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            if ("DELETED".equals(state)) return;
        }
        throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "评论删除数据服务暂不可用");
    }
}
