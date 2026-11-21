package com.freecontent.community.user.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.RoleUpdateRequest;
import com.freecontent.community.user.mapper.UserRoleUpdateMapper;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.service.read.AdminUserReader;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class UserRoleUpdateTransactionService {
    private final AdminAccountChangeGuard guard;
    private final AccountAccessService accounts;
    private final UserRoleUpdateMapper roles;
    private final AdminUserReader reader;
    private final JsonMapper json;
    private final Clock clock;

    public UserRoleUpdateTransactionService(AdminAccountChangeGuard guard, AccountAccessService accounts,
                                             UserRoleUpdateMapper roles, AdminUserReader reader, JsonMapper json, Clock clock) {
        this.guard = guard;
        this.accounts = accounts;
        this.roles = roles;
        this.reader = reader;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result replace(String userId, RoleUpdateRequest request, SessionPrincipal principal, String requestId) {
        String operatorId = guard.lockAndAuthorize(principal, "role:manage");
        var target = guard.lockTarget(userId);
        if (request.getExpectedVersion() != target.version()) throw conflict(target.version());

        var before = EnumSet.noneOf(UserRole.class);
        before.addAll(accounts.findRolesForShare(userId));
        var after = EnumSet.copyOf(request.getRoles());
        if (before.equals(after)) return new Result(reader.get(userId), null);
        if (target.version() == Integer.MAX_VALUE) throw conflict(target.version());
        if (target.authVersion() == Long.MAX_VALUE) throw unavailable();
        if (target.eligibleForAdminWrites() && before.contains(UserRole.ADMIN) && !after.contains(UserRole.ADMIN)) {
            guard.requireAnotherEffectiveAdmin(userId);
        }

        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        applyRoleDifference(userId, before, after, now);
        if (roles.advanceVersions(userId, target.version(), now) != 1) throw conflict(target.version());
        var audit = new UserRoleUpdateMapper.Audit(UUID.randomUUID().toString().replace("-", ""), userId, operatorId,
                json.writeValueAsString(before), json.writeValueAsString(after),
                target.version(), target.version() + 1, request.getReason(), requestId, now);
        if (roles.insertAudit(audit) != 1) throw unavailable();
        return new Result(reader.get(userId), target.authVersion() + 1);
    }

    private void applyRoleDifference(String userId, Set<UserRole> before, Set<UserRole> after, LocalDateTime now) {
        for (UserRole role : before) {
            if (!after.contains(role) && roles.remove(userId, role) != 1) throw unavailable();
        }
        for (UserRole role : after) {
            if (!before.contains(role) && roles.add(userId, role, now) != 1) throw unavailable();
        }
    }

    private BusinessException conflict(int version) {
        return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "账号版本已变化，请重新读取", version);
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "角色变更数据服务暂不可用");
    }

    public record Result(AdminUser user, Long revokeBeforeAuthVersion) { }
}
