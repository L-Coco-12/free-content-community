package com.freecontent.community.content.workspace.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.mapper.ContentLockMapper;
import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.content.model.persistence.ReviewRow;
import com.freecontent.community.content.model.persistence.ContentDeletionAudit;
import com.freecontent.community.content.model.persistence.ReviewEventReference;
import com.freecontent.community.content.workspace.mapper.ContentDeletionMapper;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentDeletionTransactionService {
    private final AccountAccessService accounts;
    private final ContentLockMapper locks;
    private final ContentDeletionMapper writes;
    private final Clock clock;
    private final JsonMapper json;
    public ContentDeletionTransactionService(AccountAccessService accounts, ContentLockMapper locks, ContentDeletionMapper writes, Clock clock, JsonMapper json) {
        this.accounts = accounts; this.locks = locks; this.writes = writes; this.clock = clock;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(String contentId, int expectedVersion, SessionPrincipal principal, String requestId) {
        String actor = authorize(principal);
        var content = locks.lockOwned(contentId, actor);
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if ("DELETED".equals(content.lifecycle())) return;
        if (content.version() != expectedVersion || content.version() == Integer.MAX_VALUE) throw conflict(content.version());
        var review = lockAndValidateWork(content);
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (review != null && (writes.cancelRevision(contentId, review.revisionId(), now) != 1
                || writes.invalidateReview(review.id(), contentId, review.revisionId(), review.version(), now) != 1)) throw unavailable();
        String remainingWorking = review == null ? content.workingRevisionId() : null;
        if (writes.delete(contentId, actor, content.version(), remainingWorking, now) != 1) throw conflict(content.version());
        persist(content, review, actor, remainingWorking, requestId, now);
    }

    private void persist(ContentRow content, ReviewRow review, String actor, String remainingWorking, String requestId, LocalDateTime now) {
        var audit = new ContentDeletionAudit(id(), actor, content.id(), content.publicationStatus(), content.publishedRevisionId(),
                review == null ? null : review.id(), review == null ? null : review.revisionId(), content.version(), content.version() + 1,
                review == null ? null : review.version(), review == null ? null : review.version() + 1, requestId, now);
        if (writes.insertAudit(audit) != 1) throw unavailable();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventVersion", 1); payload.put("actorId", actor); payload.put("contentId", content.id());
        payload.put("lifecycle", "DELETED"); payload.put("publicationStatus", content.publicationStatus());
        payload.put("publishedRevisionId", content.publishedRevisionId()); payload.put("contentVersion", content.version() + 1);
        payload.put("workingRevisionId", remainingWorking); payload.put("reviewId", review == null ? null : review.id());
        payload.put("deletedAt", now.toInstant(ZoneOffset.UTC)); payload.put("requestId", requestId); payload.put("occurredAt", now.toInstant(ZoneOffset.UTC));
        if (writes.insertLifecycleEvent(id(), content.id(), content.publishedRevisionId(), content.version() + 1, json.writeValueAsString(payload), now) != 1) throw unavailable();
        if (review != null) {
            var reviewPayload = new LinkedHashMap<>(payload);
            reviewPayload.put("revisionId", review.revisionId()); reviewPayload.put("reviewVersion", review.version() + 1);
            reviewPayload.put("reviewState", "INVALIDATED"); reviewPayload.put("revisionState", "CANCELLED"); reviewPayload.put("reasonCode", "AUTHOR_DELETION");
            if (writes.insertReviewEvent(id(), new ReviewEventReference(review.id(), content.id(), review.revisionId()), content.version() + 1,
                    json.writeValueAsString(reviewPayload), now) != 1) throw unavailable();
        }
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    private ReviewRow lockAndValidateWork(ContentRow content) {
        var review = writes.lockPending(content.id());
        if (!Objects.equals(content.activeReviewId(), review == null ? null : review.id())) throw unavailable();
        var working = writes.lockWorking(content.id());
        if (!Objects.equals(content.workingRevisionId(), working == null ? null : working.id())) throw unavailable();
        if (review != null) {
            if (working == null || !"IN_REVIEW".equals(working.status()) || !working.id().equals(review.revisionId()) || review.version() == Integer.MAX_VALUE) throw unavailable();
        } else if (working != null && !"DRAFT".equals(working.status())) throw unavailable();
        return review;
    }

    private String authorize(SessionPrincipal principal) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        var account = accounts.findByIdForUpdate(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified() || !accounts.findRolesForShare(account.id()).contains(UserRole.USER)) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return account.id();
    }
    private BusinessException conflict(int version) { return new BusinessException(ErrorCode.CONTENT_VERSION_CONFLICT, "内容已变化，请重新读取工作区", version); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容删除关联数据暂不可用"); }
}
