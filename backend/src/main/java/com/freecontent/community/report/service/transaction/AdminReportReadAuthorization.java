package com.freecontent.community.report.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.service.AccountAccessService;
import org.springframework.stereotype.Component;

/** 在调用方事务内复核权限，保留账号与角色记录的共享锁。 */
@Component
public class AdminReportReadAuthorization {
    private static final String PERMISSION = "report:read";
    private final AccountAccessService accounts;

    public AdminReportReadAuthorization(AccountAccessService accounts) {
        this.accounts = accounts;
    }

    void requirePermission(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified() || accounts.findRolesForShare(account.id()).stream()
                .noneMatch(role -> role.permissions().contains(PERMISSION))) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要审核员或管理员的 report:read 权限");
        }
    }
}
