package com.freecontent.community.content.comment.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.comment.mapper.CommentHideMapper;
import com.freecontent.community.content.comment.mapper.CommentHideMapper.Action;
import com.freecontent.community.content.comment.mapper.CommentHideRequestMapper;
import com.freecontent.community.content.comment.mapper.CommentHideRequestMapper.RequestRow;
import com.freecontent.community.content.moderation.dto.ModerationAction;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentHideTransactionService {
    private static final String PERMISSION = "comment:hide";
    private static final long LEASE_SECONDS = 60;
    private final CommentWriteAccess access;
    private final AccountAccessService accounts;
    private final CommentHideMapper writes;
    private final CommentHideRequestMapper requests;
    private final Clock clock;

    public CommentHideTransactionService(CommentWriteAccess access, AccountAccessService accounts,
                                         CommentHideMapper writes, CommentHideRequestMapper requests, Clock clock) {
        this.access = access;
        this.accounts = accounts;
        this.writes = writes;
        this.requests = requests;
        this.clock = clock;
    }

    public record Input(String commentId, int expectedVersion, String reason) { }
    public record Claim(String operatorId, String keyHash, String leaseToken, Input input, ModerationAction replay) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(Input input, String keyHash, String fingerprint, SessionPrincipal principal) {
        String operator = principal.user().id();
        var observed = requests.find(operator, keyHash);
        var now = now();
        // 已提交的活动租约可直接返回处理中，避免等待执行事务的记录锁。
        if (observed != null && observed.expiresAt().isAfter(now)
                && (observed.isComplete() || observed.hasLiveLease(now))) {
            authorize(principal, false);
            return existing(observed, operator, keyHash, fingerprint, input, now);
        }

        authorize(principal, true);
        var row = requests.lock(operator, keyHash);
        now = now();
        if (row != null && !row.expiresAt().isAfter(now)) {
            if (requests.deleteExpired(operator, keyHash, now) != 1) throw unavailable();
            row = null;
        }
        if (row != null) {
            var previous = existing(row, operator, keyHash, fingerprint, input, now);
            if (previous != null) return previous;
        }
        String token = id();
        var until = now.plusSeconds(LEASE_SECONDS);
        int changed = row == null
                ? requests.insert(operator, keyHash, fingerprint, token, until, now, now.plusHours(24))
                : requests.renew(operator, keyHash, token, until);
        if (changed != 1) throw unavailable();
        return new Claim(operator, keyHash, token, input, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModerationAction finish(Claim claim, SessionPrincipal principal, String requestId) {
        String operator = authorize(principal, false);
        if (!operator.equals(claim.operatorId())) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        var row = requests.lock(operator, claim.keyHash());
        var checkedAt = now();
        if (row == null || row.isComplete() || !claim.leaseToken().equals(row.leaseToken())
                || !row.hasLiveLease(checkedAt) || !row.expiresAt().isAfter(checkedAt)) {
            throw inProgress(1);
        }

        var input = claim.input();
        var target = writes.lockTarget(input.commentId());
        if (target == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (target.version() != input.expectedVersion() || target.version() == Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "评论版本已变化，请重新读取", target.version());
        }
        if (!"VISIBLE".equals(target.state())) {
            throw new BusinessException(ErrorCode.COMMENT_STATE_CONFLICT, "仅可隐藏当前可见的评论");
        }
        var changedAt = now();
        if (writes.hide(input.commentId(), target.version(), changedAt) != 1) throw unavailable();
        var action = new Action(id(), input.commentId(), operator, input.reason(), target.version(),
                target.version() + 1, requestId, changedAt);
        if (writes.insertAction(action) != 1) throw unavailable();
        if (requests.complete(operator, claim.keyHash(), claim.leaseToken(), action.id(), now()) != 1) throw inProgress(1);
        return view(action);
    }

    @Transactional
    public void release(Claim claim) {
        requests.release(claim.operatorId(), claim.keyHash(), claim.leaseToken());
    }

    private Claim existing(RequestRow row, String operator, String keyHash, String fingerprint, Input input, LocalDateTime now) {
        if (!row.fingerprint().equals(fingerprint)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        if (row.isComplete()) {
            var action = writes.findAction(row.actionId());
            if (action == null || !operator.equals(action.operatorId()) || !input.commentId().equals(action.commentId())
                    || input.expectedVersion() != action.beforeVersion() || !input.reason().equals(action.reason())) {
                throw unavailable();
            }
            return new Claim(operator, keyHash, null, input, view(action));
        }
        if (row.hasLiveLease(now)) {
            long millis = Duration.between(now, row.leaseUntil()).toMillis();
            throw inProgress(Math.max(1, (millis + 999) / 1000));
        }
        return null;
    }

    private String authorize(SessionPrincipal principal, boolean exclusive) {
        String operator = access.authorize(principal, exclusive);
        if (accounts.findRolesForShare(operator).stream().noneMatch(role -> role.permissions().contains(PERMISSION))) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要审核员或管理员的 comment:hide 权限");
        }
        return operator;
    }

    private static ModerationAction view(Action action) {
        return new ModerationAction(action.id(), "COMMENT", action.commentId(), "HIDE_COMMENT", action.reason(),
                action.operatorId(), action.createdAt().toInstant(ZoneOffset.UTC), action.resultingVersion());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static BusinessException inProgress(long seconds) {
        return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同隐藏评论请求正在处理", null, seconds, List.of());
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "评论隐藏数据服务暂不可用");
    }
}
