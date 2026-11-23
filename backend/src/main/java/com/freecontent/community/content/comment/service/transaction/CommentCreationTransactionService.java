package com.freecontent.community.content.comment.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.comment.dto.CommentView;
import com.freecontent.community.content.comment.mapper.CommentCreationRequestMapper;
import com.freecontent.community.content.comment.mapper.CommentCreationRequestMapper.RequestRow;
import com.freecontent.community.content.comment.mapper.CommentMapper;
import com.freecontent.community.content.comment.mapper.CommentMapper.CommentInsert;
import com.freecontent.community.content.comment.mapper.CommentMapper.CommentTarget;
import com.freecontent.community.content.comment.service.read.CommentAuthorReader;
import com.freecontent.community.content.service.read.PublicContentVisibilityReader;
import com.freecontent.community.notification.service.CommentReplyNotificationWriter;
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
import tools.jackson.databind.json.JsonMapper;

@Service
public class CommentCreationTransactionService {
    private static final long LEASE_SECONDS = 60;
    private final CommentWriteAccess access;
    private final CommentCreationRequestMapper requests;
    private final PublicContentVisibilityReader visibility;
    private final CommentMapper comments;
    private final CommentReplyNotificationWriter notifications;
    private final CommentAuthorReader authors;
    private final JsonMapper json;
    private final Clock clock;

    public CommentCreationTransactionService(CommentWriteAccess access, CommentCreationRequestMapper requests,
                                            PublicContentVisibilityReader visibility, CommentMapper comments,
                                            CommentReplyNotificationWriter notifications, CommentAuthorReader authors,
                                            JsonMapper json, Clock clock) {
        this.access = access;
        this.requests = requests;
        this.visibility = visibility;
        this.comments = comments;
        this.notifications = notifications;
        this.authors = authors;
        this.json = json;
        this.clock = clock;
    }

    public record Input(String contentId, String body, String replyToCommentId) { }
    public record Claim(String actorId, String keyHash, String leaseToken, Input input, CommentView replay) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(Input input, String keyHash, String fingerprint, SessionPrincipal principal) {
        String actor = principal.user().id();
        var observed = requests.find(actor, keyHash);
        var now = now();
        // 已提交的租约可以直接判断处理中，无需等待正在写评论的事务释放幂等记录锁。
        if (observed != null && observed.expiresAt().isAfter(now)
                && (observed.isComplete() || observed.hasLiveLease(now))) {
            access.authorize(principal, false);
            return existing(observed, actor, keyHash, fingerprint, input, now);
        }

        access.authorize(principal, true);
        var row = requests.lock(actor, keyHash);
        now = now();
        if (row != null && !row.expiresAt().isAfter(now)) {
            if (requests.deleteExpired(actor, keyHash, now) != 1) throw unavailable();
            row = null;
        }
        if (row != null) {
            var previous = existing(row, actor, keyHash, fingerprint, input, now);
            if (previous != null) return previous;
        }

        String token = id();
        var until = now.plusSeconds(LEASE_SECONDS);
        int changed = row == null
                ? requests.insert(actor, keyHash, fingerprint, token, until, now, now.plusHours(24))
                : requests.renew(actor, keyHash, token, until);
        if (changed != 1) throw unavailable();
        return new Claim(actor, keyHash, token, input, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommentView finish(Claim claim, SessionPrincipal principal) {
        String actor = access.authorize(principal, false);
        if (!actor.equals(claim.actorId())) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        var row = requests.lock(actor, claim.keyHash());
        var checkedAt = now();
        if (row == null || row.isComplete() || !claim.leaseToken().equals(row.leaseToken())
                || !row.hasLiveLease(checkedAt) || !row.expiresAt().isAfter(checkedAt)) {
            throw inProgress(1);
        }

        Input input = claim.input();
        visibility.requireCurrentlyVisible(input.contentId());
        CommentTarget target = input.replyToCommentId() == null
                ? null : requireVisibleComment(input.contentId(), input.replyToCommentId());
        String rootId = target == null ? null : target.rootCommentId() == null ? target.id() : target.rootCommentId();
        var comment = new CommentInsert(id(), input.contentId(), actor, rootId, input.replyToCommentId(), input.body(), now());
        if (comments.insert(comment) != 1) throw unavailable();

        if (target != null && !actor.equals(target.authorId())) {
            notifications.create(target.authorId(), comment.id(), comment.createdAt());
        }
        var view = new CommentView(comment.id(), comment.contentId(), comment.rootCommentId(), comment.replyToCommentId(),
                authors.read(actor), comment.body(), "VISIBLE", 0, comment.createdAt().toInstant(ZoneOffset.UTC));
        if (requests.complete(actor, claim.keyHash(), claim.leaseToken(), comment.id(), json.writeValueAsString(view), now()) != 1) {
            throw inProgress(1);
        }
        return view;
    }

    @Transactional
    public void release(Claim claim) {
        requests.release(claim.actorId(), claim.keyHash(), claim.leaseToken());
    }

    private Claim existing(RequestRow row, String actor, String keyHash, String fingerprint, Input input, LocalDateTime now) {
        if (!row.fingerprint().equals(fingerprint)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        if (row.isComplete()) {
            visibility.requireCurrentlyVisible(input.contentId());
            var comment = requireVisibleComment(input.contentId(), row.commentId());
            if (!actor.equals(comment.authorId())) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            var replay = readSnapshot(row.resultSnapshot());
            if (!comment.id().equals(replay.id()) || !input.contentId().equals(replay.contentId())
                    || replay.author() == null || !actor.equals(replay.author().id())) throw unavailable();
            return new Claim(actor, keyHash, null, input, replay);
        }
        if (row.hasLiveLease(now)) {
            long millis = Duration.between(now, row.leaseUntil()).toMillis();
            throw inProgress(Math.max(1, (millis + 999) / 1000));
        }
        return null;
    }

    private CommentView readSnapshot(String snapshot) {
        try {
            var result = json.readValue(snapshot, CommentView.class);
            if (result != null) return result;
        } catch (RuntimeException ex) {
            throw unavailable();
        }
        throw unavailable();
    }

    private CommentTarget requireVisibleComment(String contentId, String commentId) {
        var target = comments.lockTarget(contentId, commentId);
        if (target == null || !"VISIBLE".equals(target.state())) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return target;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "评论发布数据服务暂不可用");
    }

    private static BusinessException inProgress(long retryAfter) {
        return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同评论发布请求正在处理", null, retryAfter, List.of());
    }
}
