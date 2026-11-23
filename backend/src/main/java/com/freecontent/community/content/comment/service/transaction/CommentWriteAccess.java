package com.freecontent.community.content.comment.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.service.AccountAccessService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CommentWriteAccess {
    private final AccountAccessService accounts;

    public CommentWriteAccess(AccountAccessService accounts) {
        this.accounts = accounts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String authorize(SessionPrincipal principal, boolean exclusive) {
        String actor = principal.user().id();
        var account = exclusive ? accounts.findByIdForUpdate(actor) : accounts.findByIdForShare(actor);
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified()) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return account.id();
    }
}
