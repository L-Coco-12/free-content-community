package com.freecontent.community.content.comment.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.comment.dto.AdminComment;
import com.freecontent.community.content.comment.dto.AdminCommentPage;
import com.freecontent.community.content.comment.mapper.AdminCommentQueryMapper;
import com.freecontent.community.content.comment.mapper.AdminCommentQueryMapper.CommentRow;
import com.freecontent.community.user.service.AccountAccessService;
import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCommentQueryTransactionService {
    private static final String PERMISSION = "comment:moderate:read";
    private final AccountAccessService accounts;
    private final AdminCommentQueryMapper comments;

    public AdminCommentQueryTransactionService(AccountAccessService accounts, AdminCommentQueryMapper comments) {
        this.accounts = accounts;
        this.comments = comments;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminCommentPage list(BigInteger page, int pageSize, String contentId, String authorId, String state,
                                 SessionPrincipal principal) {
        authorize(principal);
        long total = comments.count(contentId, authorId, state);
        var offset = page.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(pageSize));
        if (offset.compareTo(BigInteger.valueOf(total)) >= 0) {
            return new AdminCommentPage(List.of(), page, pageSize, total);
        }

        var items = comments.page(contentId, authorId, state, offset.longValueExact(), pageSize).stream()
                .map(AdminCommentQueryTransactionService::view)
                .toList();
        return new AdminCommentPage(items, page, pageSize, total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminComment read(String commentId, SessionPrincipal principal) {
        authorize(principal);
        var row = comments.findById(commentId);
        if (row == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return view(row);
    }

    private static AdminComment view(CommentRow row) {
        return new AdminComment(row.id(), row.contentId(), row.authorId(), row.body(), row.state(),
                row.version(), row.createdAt().toInstant(ZoneOffset.UTC));
    }

    private void authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified() || accounts.findRolesForShare(account.id()).stream()
                .noneMatch(role -> role.permissions().contains(PERMISSION))) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要审核员或管理员的 comment:moderate:read 权限");
        }
    }
}
