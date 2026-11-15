package com.freecontent.community.content.workspace.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.mapper.ContentLockMapper;
import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.content.model.persistence.PublicationWithdrawalAudit;
import com.freecontent.community.content.model.persistence.ReviewEventReference;
import com.freecontent.community.content.model.persistence.ReviewRow;
import com.freecontent.community.content.workspace.mapper.PublicationWithdrawalMapper;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 内容、审核、修订、审计和事件同事务提交；无副作用目标状态先于新执行的版本检查。 */
@Service
public class PublicationWithdrawalTransactionService {
    private static final Set<String> ALREADY_NON_PUBLIC = Set.of("WITHDRAWN", "UNPUBLISHED");
    private final AccountAccessService accounts;
    private final ContentLockMapper locks;
    private final PublicationWithdrawalMapper writes;
    private final Clock clock;
    private final JsonMapper json;
    public PublicationWithdrawalTransactionService(AccountAccessService accounts, ContentLockMapper locks, PublicationWithdrawalMapper writes, Clock clock, JsonMapper json) {
        this.accounts = accounts; this.locks = locks; this.writes = writes; this.clock = clock;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void withdraw(String contentId, int expectedVersion, SessionPrincipal principal, String requestId) {
        String actor = authorize(principal);
        var content = locks.lockOwned(contentId, actor);
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!"ACTIVE".equals(content.lifecycle())) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        if ("TAKEN_DOWN".equals(content.publicationStatus())) throw new BusinessException(ErrorCode.REMEDIATION_REQUIRED);
        if (ALREADY_NON_PUBLIC.contains(content.publicationStatus())) return;
        if (content.version() != expectedVersion || content.version() == Integer.MAX_VALUE) throw conflict(content.version());
        var review = lockAndValidateWork(content);
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime now = LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
        if (review != null && (writes.cancelRevision(contentId, review.revisionId(), now) != 1
                || writes.invalidateReview(review.id(), contentId, review.revisionId(), review.version(), now) != 1)) throw unavailable();
        String remainingWorking = review == null ? content.workingRevisionId() : null;
        if (writes.withdraw(contentId, actor, content.version(), remainingWorking, now) != 1) throw conflict(content.version());
        persist(content, review, actor, remainingWorking, requestId, occurredAt, now);
    }

    private ReviewRow lockAndValidateWork(ContentRow content) {
        String contentId = content.id();
        var review = writes.lockPending(contentId);
        if (!Objects.equals(content.activeReviewId(), review == null ? null : review.id())) throw unavailable();
        var working = writes.lockWorking(contentId);
        if (!Objects.equals(content.workingRevisionId(), working == null ? null : working.id())) throw unavailable();
        var published = content.publishedRevisionId() == null ? null : locks.lockRevision(content.publishedRevisionId(), contentId);
        if (published == null || !"APPROVED".equals(published.status())) throw unavailable();
        if (review != null) {
            if (working == null || !"IN_REVIEW".equals(working.status()) || !working.id().equals(review.revisionId())) throw unavailable();
            if (review.version() == Integer.MAX_VALUE) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE, "审核版本已达上限");
        } else if (working != null && !"DRAFT".equals(working.status())) throw unavailable();
        return review;
    }

    private void persist(ContentRow content, ReviewRow review, String actor, String remainingWorking, String requestId,
            Instant occurredAt, LocalDateTime now) {
        var audit = new PublicationWithdrawalAudit(id(), actor, content.id(), content.publishedRevisionId(),
                review == null ? null : review.id(), review == null ? null : review.revisionId(), content.version(), content.version() + 1,
                review == null ? null : review.version(), review == null ? null : review.version() + 1, requestId, now);
        if (writes.insertAudit(audit) != 1) throw unavailable();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventVersion", 1); payload.put("actorId", actor); payload.put("contentId", content.id());
        payload.put("publishedRevisionId", content.publishedRevisionId()); payload.put("publicationStatus", "WITHDRAWN");
        payload.put("contentVersion", content.version() + 1); payload.put("workingRevisionId", remainingWorking);
        payload.put("reviewId", review == null ? null : review.id()); payload.put("requestId", requestId); payload.put("occurredAt", occurredAt);
        if (writes.insertPublicationEvent(id(), content.id(), content.publishedRevisionId(), content.version() + 1, json.writeValueAsString(payload), now) != 1) throw unavailable();
        if (review != null) {
            var reviewPayload = new LinkedHashMap<>(payload);
            reviewPayload.put("revisionId", review.revisionId()); reviewPayload.put("reviewVersion", review.version() + 1);
            reviewPayload.put("reviewState", "INVALIDATED"); reviewPayload.put("revisionState", "CANCELLED"); reviewPayload.put("reasonCode", "AUTHOR_WITHDRAWAL");
            if (writes.insertReviewEvent(id(), new ReviewEventReference(review.id(), content.id(), review.revisionId()),
                    content.version() + 1, json.writeValueAsString(reviewPayload), now) != 1) throw unavailable();
        }
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
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "公开撤回关联数据暂不可用"); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
