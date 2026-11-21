package com.freecontent.community.user.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.RoleDefinition;
import com.freecontent.community.user.dto.RoleList;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminRoleQueryTransactionService {
    private static final RoleList CATALOG = new RoleList(Arrays.stream(UserRole.values())
            .map(role -> new RoleDefinition(role, role.displayName(), role.permissions()))
            .toList());

    private final AccountAccessService accounts;

    public AdminRoleQueryTransactionService(AccountAccessService accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public RoleList list(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        }
        if (!account.emailVerified() || !accounts.findRolesForShare(account.id()).contains(UserRole.ADMIN)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要管理员的role:manage权限");
        }
        return CATALOG;
    }
}
