package com.freecontent.community.content.moderation.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.mapper.ContentLockMapper;
import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.content.model.persistence.ReviewRow;
import com.freecontent.community.content.model.persistence.RevisionRow;
import com.freecontent.community.content.model.persistence.ContentModerationActionRow;
import com.freecontent.community.content.model.persistence.TakeDownAudit;
import com.freecontent.community.content.model.persistence.ReviewEventReference;
import com.freecontent.community.content.moderation.dto.*;
import com.freecontent.community.content.moderation.mapper.ContentTakeDownMapper;
import com.freecontent.community.content.moderation.mapper.ContentTakeDownRequestMapper;
import com.freecontent.community.content.moderation.entity.ContentTakeDownRequestRecord;
import com.freecontent.community.notification.service.ModerationNotificationWriter;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.Objects;
import java.util.List;
import java.util.LinkedHashMap;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ContentTakeDownTransactionService {
    private final AccountAccessService accounts;
    private final ContentTakeDownMapper writes;
    private final ContentLockMapper revisions;
    private final Clock clock;
    private final ContentTakeDownRequestMapper requests;
    private final ModerationNotificationWriter notifications;
    private final JsonMapper json;
    public ContentTakeDownTransactionService(AccountAccessService accounts, ContentTakeDownMapper writes, ContentLockMapper revisions, Clock clock,
            ContentTakeDownRequestMapper requests,ModerationNotificationWriter notifications,JsonMapper json) {
        this.accounts = accounts; this.writes = writes; this.revisions = revisions; this.clock = clock;
        this.requests = requests; this.notifications = notifications; this.json = json;
    }
    public record Selection(int expectedVersion,String reasonCode,String reason) { }
    public record Claim(ContentTakeDownRequestRecord request,String contentId,Selection input,ModerationAction replay) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(String contentId,TakeDownRequest input,String keyHash,String fingerprint,SessionPrincipal principal) {
        String actor = authorize(principal);
        var selection = new Selection(input.getExpectedVersion(),input.getReasonCode().name(),input.getReason());
        var now = now();
        var record = requests.lockByKey(actor,keyHash);
        if (record != null && !record.getExpiresAt().isAfter(now)) { requests.deleteById(record.getId()); record = null; }
        if (record != null) {
            if (!Objects.equals(record.getFingerprint(),fingerprint)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            if ("SUCCEEDED".equals(record.getState())) return new Claim(record,contentId,selection,replay(record,contentId,selection,actor));
            if (record.getLeaseUntil() != null && record.getLeaseUntil().isAfter(now)) throw inProgress(Math.max(1,(Duration.between(now,record.getLeaseUntil()).toMillis()+999)/1000));
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            if (requests.renew(record.getId(),record.getLeaseToken(),record.getLeaseUntil()) != 1) throw inProgress(1);
        } else {
            record = new ContentTakeDownRequestRecord(); record.setId(id()); record.setCreatedBy(actor); record.setKeyHash(keyHash); record.setFingerprint(fingerprint);
            record.setState("PROCESSING"); record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60)); record.setCreatedAt(now); record.setExpiresAt(now.plusHours(24));
            if (requests.insert(record) != 1) throw unavailable();
        }
        return new Claim(record,contentId,selection,null);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModerationAction finish(Claim claim,SessionPrincipal principal,String requestId) {
        String actor = authorize(principal); var checkedAt = now();
        var record = requests.lockByKey(actor,claim.request().getKeyHash());
        if (record == null || !record.getId().equals(claim.request().getId()) || !"PROCESSING".equals(record.getState())
                || !Objects.equals(record.getLeaseToken(),claim.request().getLeaseToken()) || record.getLeaseUntil() == null
                || !record.getLeaseUntil().isAfter(checkedAt) || !record.getExpiresAt().isAfter(checkedAt)) throw inProgress(1);
        var result = apply(claim.contentId(),claim.input(),actor,requestId);
        if (requests.complete(record.getId(),record.getLeaseToken(),result.targetId(),result.id(),json.writeValueAsString(result),now()) != 1) throw inProgress(1);
        return result;
    }
    @Transactional
    public void release(Claim claim) { requests.release(claim.request().getId(),claim.request().getLeaseToken()); }

    private ModerationAction replay(ContentTakeDownRequestRecord record,String content,Selection input,String actor) {
        var row = record.getActionId() == null ? null : writes.findAction(record.getActionId());
        if (row == null || !content.equals(record.getContentId()) || !content.equals(row.contentId()) || !actor.equals(row.operatorId())
                || !input.reasonCode().equals(row.reasonCode()) || !input.reason().equals(row.reason()) || row.resultingVersion() != (long) input.expectedVersion()+1) throw unavailable();
        ModerationAction snapshot;
        try { snapshot = json.readValue(record.getResultSnapshot(),ModerationAction.class); }
        catch (RuntimeException failure) { throw unavailable(); }
        if (!view(row).equals(snapshot)) throw unavailable();
        return snapshot;
    }
    private ModerationAction apply(String contentId,Selection input,String actor,String requestId) {
        // 通知收件人外键会读取账号；先取得共享账号锁，再锁内容，避免与作者写入的账号→内容锁序相反。
        String author = writes.findAuthor(contentId);
        if (author == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (accounts.findByIdForShare(author) == null) throw unavailable();
        var content = writes.lockContent(contentId);
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!author.equals(content.authorId())) throw unavailable();
        if ("TAKEN_DOWN".equals(content.publicationStatus())) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        if (content.publishedRevisionId() == null) {
            if ("UNPUBLISHED".equals(content.publicationStatus())) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
            throw unavailable();
        }
        if (content.version() != input.expectedVersion() || content.version() == Integer.MAX_VALUE) throw conflict(content.version());
        if (!List.of("PUBLISHED","WITHDRAWN").contains(content.publicationStatus())) throw unavailable();
        var work = lockAndValidateWork(content);
        var published = revisions.lockRevision(content.publishedRevisionId(),contentId);
        if (published == null || !"APPROVED".equals(published.status())) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"历史发布稿暂不可用");
        var now = now();
        if (work.working() != null && writes.cancelWorking(contentId,work.working().id(),now) != 1) throw unavailable();
        if (work.review() != null && writes.invalidateReview(work.review().id(),contentId,work.review().revisionId(),work.review().version(),now) != 1) throw unavailable();
        if (writes.takeDown(contentId,content.version(),now) != 1) throw conflict(content.version());
        var action = new ContentModerationActionRow(id(),contentId,input.reasonCode(),input.reason(),actor,content.version()+1,now);
        persist(content,work,action,requestId,now);
        return view(action);
    }
    private void persist(ContentRow content,Work work,ContentModerationActionRow action,String requestId,LocalDateTime now) {
        if (writes.insertAction(action) != 1) throw unavailable();
        var working = work.working(); var review = work.review();
        if (writes.insertAudit(new TakeDownAudit(id(),action.id(),content.id(),action.operatorId(),content.publishedRevisionId(),
                working == null ? null : working.id(),working == null ? null : working.status(),review == null ? null : review.id(),content.version(),action.resultingVersion(),
                review == null ? null : review.version(),review == null ? null : review.version()+1,requestId,now)) != 1) throw unavailable();
        String eventId = id();
        String notificationId = notifications.create(content.authorId(),content.id(),eventId,action.reason(),now);
        var payload = new LinkedHashMap<String,Object>();
        payload.put("eventVersion",1); payload.put("eventId",eventId); payload.put("actorId",action.operatorId()); payload.put("contentId",content.id());
        payload.put("lifecycle",content.lifecycle()); payload.put("publicationStatus","TAKEN_DOWN"); payload.put("publishedRevisionId",content.publishedRevisionId());
        payload.put("contentVersion",action.resultingVersion()); payload.put("moderationActionId",action.id()); payload.put("reasonCode",action.reasonCode());
        payload.put("cancelledRevisionId",working == null ? null : working.id()); payload.put("reviewId",review == null ? null : review.id());
        payload.put("notificationId",notificationId); payload.put("requestId",requestId); payload.put("occurredAt",now.toInstant(ZoneOffset.UTC));
        if (writes.insertPublicationEvent(eventId,content.id(),content.publishedRevisionId(),action.resultingVersion(),json.writeValueAsString(payload),now) != 1) throw unavailable();
        if (review != null) {
            var invalidation = new LinkedHashMap<>(payload); invalidation.put("revisionId",review.revisionId()); invalidation.put("reviewVersion",review.version()+1);
            invalidation.put("reviewState","INVALIDATED"); invalidation.put("revisionState","CANCELLED"); invalidation.put("reasonCode","MODERATION_TAKE_DOWN");
            if (writes.insertReviewEvent(id(),new ReviewEventReference(review.id(),content.id(),review.revisionId()),action.resultingVersion(),json.writeValueAsString(invalidation),now) != 1) throw unavailable();
        }
    }
    private ModerationAction view(ContentModerationActionRow row) {
        return new ModerationAction(row.id(),"CONTENT",row.contentId(),"TAKE_DOWN",row.reason(),row.operatorId(),row.createdAt().toInstant(ZoneOffset.UTC),row.resultingVersion());
    }
    private record Work(RevisionRow working,ReviewRow review) { }
    private Work lockAndValidateWork(ContentRow content) {
        var review = writes.lockPending(content.id());
        if (!Objects.equals(content.activeReviewId(),review == null ? null : review.id())) throw unavailable();
        var working = writes.lockWorking(content.id());
        if (!Objects.equals(content.workingRevisionId(),working == null ? null : working.id())) throw unavailable();
        if (review != null) {
            if (working == null || !"IN_REVIEW".equals(working.status()) || !working.id().equals(review.revisionId()) || review.version() == Integer.MAX_VALUE) throw unavailable();
        } else if (working != null && !"DRAFT".equals(working.status())) throw unavailable();
        return new Work(working,review);
    }
    private String authorize(SessionPrincipal principal) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified() || accounts.findRolesForShare(account.id()).stream().noneMatch(role -> role == UserRole.MODERATOR || role == UserRole.ADMIN))
            throw new BusinessException(ErrorCode.PERMISSION_DENIED,"需要审核员或管理员的content:take-down权限");
        return account.id();
    }
    private BusinessException conflict(int version) { return new BusinessException(ErrorCode.CONTENT_VERSION_CONFLICT,"内容已变化，请重新读取工作区",version); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"下架关联数据暂不可用"); }
    private BusinessException inProgress(long seconds) { return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS,"相同下架请求正在处理",null,seconds,List.of()); }
    private String id() { return UUID.randomUUID().toString().replace("-",""); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
}
