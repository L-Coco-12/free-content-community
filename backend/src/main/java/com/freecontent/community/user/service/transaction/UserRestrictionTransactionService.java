package com.freecontent.community.user.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.RestrictionRequest;
import com.freecontent.community.user.entity.UserAccount.Status;
import com.freecontent.community.user.mapper.UserRestrictionMapper;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.service.read.AdminUserReader;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRestrictionTransactionService {
    private final AdminAccountChangeGuard guard;
    private final AccountAccessService accounts;
    private final UserRestrictionMapper restrictions;
    private final AdminUserReader reader;
    private final Clock clock;

    public UserRestrictionTransactionService(AdminAccountChangeGuard guard, AccountAccessService accounts, UserRestrictionMapper restrictions,
                                              AdminUserReader reader, Clock clock) {
        this.guard = guard;
        this.accounts = accounts;
        this.restrictions = restrictions;
        this.reader = reader;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result restrict(String userId, RestrictionRequest request, SessionPrincipal principal, String requestId) {
        String operatorId = guard.lockAndAuthorize(principal, "user:restrict");
        var target = guard.lockTarget(userId);
        if (request.getExpectedVersion() != target.version()) throw conflict(target.version());

        Status next = request.getStatus();
        if (next == target.accountStatus()) return new Result(reader.get(userId), null);
        if (target.version() == Integer.MAX_VALUE) throw conflict(target.version());
        if (target.eligibleForAdminWrites() && next != Status.ACTIVE
                && accounts.findRolesForShare(userId).contains(UserRole.ADMIN)) {
            guard.requireAnotherEffectiveAdmin(userId);
        }

        boolean revoke = next == Status.DISABLED;
        if (revoke && target.authVersion() == Long.MAX_VALUE) throw unavailable();
        long authVersion = revoke ? target.authVersion() + 1 : target.authVersion();
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (restrictions.update(userId, next, target.version(), authVersion, now) != 1) {
            throw conflict(target.version());
        }
        var audit = new UserRestrictionMapper.Audit(UUID.randomUUID().toString().replace("-", ""),
                userId, operatorId, target.accountStatus(), next, target.version(), target.version() + 1,
                request.getReason(), requestId, now);
        if (restrictions.insertAudit(audit) != 1) throw unavailable();
        return new Result(reader.get(userId), revoke ? authVersion : null);
    }

    private BusinessException conflict(int version) {
        return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "账号状态或版本已变化，请重新读取", version);
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "账号限制数据服务暂不可用");
    }

    public record Result(AdminUser user, Long revokeBeforeAuthVersion) { }
}
