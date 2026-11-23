package com.freecontent.community.content.moderation.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.moderation.dto.ModerationAction;
import com.freecontent.community.content.moderation.mapper.ModerationActionQueryMapper;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModerationActionQueryTransactionService {
    private static final String PERMISSION = "moderation:read";
    private final AccountAccessService accounts;
    private final ModerationActionQueryMapper actions;

    public ModerationActionQueryTransactionService(AccountAccessService accounts, ModerationActionQueryMapper actions) {
        this.accounts = accounts;
        this.actions = actions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModerationAction read(String actionId, SessionPrincipal principal) {
        authorize(principal);
        var records = actions.findById(actionId);
        if (records.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (records.size() != 1) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "处置记录暂不可用");
        }
        var row = records.get(0);
        return new ModerationAction(row.id(), row.targetType(), row.targetId(), row.action(), row.reason(),
                row.operatorId(), row.createdAt().toInstant(ZoneOffset.UTC), row.resultingVersion());
    }

    private void authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified() || accounts.findRolesForShare(account.id()).stream()
                .noneMatch(role -> role.permissions().contains(PERMISSION))) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要审核员或管理员的 moderation:read 权限");
        }
    }
}
