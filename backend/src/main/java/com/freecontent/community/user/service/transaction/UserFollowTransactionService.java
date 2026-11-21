package com.freecontent.community.user.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.notification.service.FollowerNotificationWriter;
import com.freecontent.community.user.mapper.UserFollowMapper;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserFollowTransactionService {
    private final AccountAccessService accounts;
    private final UserFollowMapper follows;
    private final FollowerNotificationWriter notifications;
    private final Clock clock;

    public UserFollowTransactionService(AccountAccessService accounts, UserFollowMapper follows,
                                        FollowerNotificationWriter notifications, Clock clock) {
        this.accounts = accounts;
        this.follows = follows;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void follow(String targetId, SessionPrincipal actor) {
        String followerId = requireActiveActor(actor);
        if (followerId.equals(targetId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能关注自己", null, null,
                    List.of(new ProblemDetailResponse.Violation("path", "userId", "不能关注自己")));
        }
        var target = accounts.findByIdForShare(targetId);
        if (target == null || target.disabled()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        String id = UUID.randomUUID().toString().replace("-", "");
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            if (follows.insert(id, followerId, targetId, now) != 1) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "关注关系暂不可用");
            }
        } catch (DuplicateKeyException ex) {
            // 仅当这一对用户的关系已经存在时，才把重复键视为幂等成功。
            if (follows.findId(followerId, targetId) != null) return;
            throw ex;
        }
        notifications.create(targetId, id, actor.user().displayName(), now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unfollow(String targetId, SessionPrincipal actor) {
        String followerId = requireActiveActor(actor);
        follows.delete(followerId, targetId);
    }

    private String requireActiveActor(SessionPrincipal actor) {
        String followerId = actor.user().id();
        var follower = accounts.findByIdForShare(followerId);
        if (follower == null || follower.authVersion() == null || follower.authVersion() != actor.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (!follower.active()) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        }
        if (!follower.emailVerified()) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
        return followerId;
    }
}
