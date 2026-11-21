package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.RoleUpdateRequest;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.ratelimit.UserRoleUpdateRateLimiter;
import com.freecontent.community.user.service.UserRoleUpdateService;
import com.freecontent.community.user.service.transaction.UserRoleUpdateTransactionService;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class UserRoleUpdateServiceImpl implements UserRoleUpdateService {
    private static final Logger log = LoggerFactory.getLogger(UserRoleUpdateServiceImpl.class);
    private final UserRoleUpdateTransactionService transactions;
    private final UserRoleUpdateRateLimiter limiter;
    private final RedisSessionStore sessions;

    public UserRoleUpdateServiceImpl(UserRoleUpdateTransactionService transactions,
                                     UserRoleUpdateRateLimiter limiter, RedisSessionStore sessions) {
        this.transactions = transactions;
        this.limiter = limiter;
        this.sessions = sessions;
    }

    @Override
    public AdminUser replace(String userId, RoleUpdateRequest request, SessionPrincipal principal,
                             String sourceIp, String requestId) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "用户ID格式不正确", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "userId", "仅允许1到64位字母、数字、下划线及短横线")));
        }
        validateRoles(request.getRoles());
        limiter.acquire(principal.user().id(), sourceIp);
        UserRoleUpdateTransactionService.Result result;
        try {
            result = transactions.replace(userId, request, principal, requestId);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "角色变更数据服务暂不可用");
        }

        if (result.revokeBeforeAuthVersion() != null) {
            try {
                sessions.revokeBeforeVersion(userId, result.revokeBeforeAuthVersion());
            } catch (BusinessException ex) {
                if (ex.errorCode() != ErrorCode.DEPENDENCY_UNAVAILABLE) throw ex;
                // 数据库中已提交的新认证版本会拒绝旧会话。
                log.warn("Role change committed; Redis session cleanup failed, userId={}", userId);
            }
        }
        return result.user();
    }

    private void validateRoles(List<UserRole> roles) {
        if (roles == null || !roles.contains(UserRole.USER) || roles.stream().anyMatch(Objects::isNull)
                || new HashSet<>(roles).size() != roles.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "角色集合不合法", null, null,
                    List.of(ProblemDetailResponse.Violation.body("roles", "必须包含USER，角色不能重复或为空")));
        }
    }
}
