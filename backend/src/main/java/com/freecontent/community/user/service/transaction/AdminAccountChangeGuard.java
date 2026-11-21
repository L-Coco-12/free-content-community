package com.freecontent.community.user.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.mapper.AdminAccountChangeMapper;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 账号状态与角色变更共用锁序，防止并发操作令有效管理员数归零。 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class AdminAccountChangeGuard {
    private final AdminAccountChangeMapper changes;
    private final AccountAccessService accounts;

    public AdminAccountChangeGuard(AdminAccountChangeMapper changes, AccountAccessService accounts) {
        this.changes = changes;
        this.accounts = accounts;
    }

    public String lockAndAuthorize(SessionPrincipal principal, String permission) {
        if (changes.lockAdminAccountChanges() == null) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "账号管理数据服务暂不可用");
        }
        var account = accounts.findByIdForUpdate(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified() || !accounts.findRolesForShare(account.id()).contains(UserRole.ADMIN)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要管理员的" + permission + "权限");
        }
        return account.id();
    }

    public AdminAccountChangeMapper.Target lockTarget(String userId) {
        var target = changes.lockTarget(userId);
        if (target == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return target;
    }

    public void requireAnotherEffectiveAdmin(String userId) {
        if (!changes.hasOtherEffectiveAdmin(userId)) {
            throw new BusinessException(ErrorCode.LAST_ADMIN_PROTECTED);
        }
    }
}
