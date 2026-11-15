package com.freecontent.community.content.workspace.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.mapper.ContentLockMapper;
import com.freecontent.community.content.mapper.ContentReadMapper;
import com.freecontent.community.content.service.read.ContentWorkspaceReader;
import com.freecontent.community.content.workspace.mapper.ContentRestorationMapper;
import com.freecontent.community.content.workspace.dto.VersionRequest;
import com.freecontent.community.content.workspace.entity.ContentRestorationRequestRecord;
import com.freecontent.community.content.workspace.mapper.ContentRestorationRequestMapper;
import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.content.model.persistence.ContentRestorationAudit;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentRestorationTransactionService {
    private final AccountAccessService accounts;
    private final ContentLockMapper locks;
    private final ContentReadMapper reads;
    private final ContentWorkspaceReader workspaces;
    private final ContentRestorationMapper writes;
    private final Clock clock;
    private final ContentRestorationRequestMapper requests;
    private final JsonMapper json;
    public ContentRestorationTransactionService(AccountAccessService accounts, ContentLockMapper locks, ContentReadMapper reads,
            ContentWorkspaceReader workspaces, ContentRestorationMapper writes, Clock clock, ContentRestorationRequestMapper requests, JsonMapper json) {
        this.accounts = accounts; this.locks = locks; this.reads = reads; this.workspaces = workspaces; this.writes = writes; this.clock = clock;
        this.requests = requests; this.json = json;
    }

    public record Claim(ContentRestorationRequestRecord request, String contentId, int expectedVersion, ContentWorkspace replay) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(String contentId, VersionRequest input, String keyHash, String fingerprint, SessionPrincipal principal) {
        String actor = authorize(principal);
        LocalDateTime now = now();
        var record = requests.lockByKey(actor, keyHash);
        if (record != null && !record.getExpiresAt().isAfter(now)) { requests.deleteById(record.getId()); record = null; }
        if (record != null) {
            if (!Objects.equals(record.getFingerprint(), fingerprint)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            if ("SUCCEEDED".equals(record.getState())) return new Claim(record, contentId, input.getExpectedVersion(), replay(record, actor, contentId, input.getExpectedVersion()));
            if (record.getLeaseUntil() != null && record.getLeaseUntil().isAfter(now))
                throw inProgress(Math.max(1, (Duration.between(now, record.getLeaseUntil()).toMillis() + 999) / 1000));
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            if (requests.renew(record.getId(), record.getLeaseToken(), record.getLeaseUntil()) != 1) throw inProgress(1);
        } else {
            record = new ContentRestorationRequestRecord();
            record.setId(id()); record.setCreatedBy(actor); record.setKeyHash(keyHash); record.setFingerprint(fingerprint);
            record.setState("PROCESSING"); record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            record.setCreatedAt(now); record.setExpiresAt(now.plusHours(24));
            if (requests.insert(record) != 1) throw unavailable();
        }
        return new Claim(record, contentId, input.getExpectedVersion(), null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContentWorkspace finish(Claim claim, SessionPrincipal principal, String requestId) {
        String actor = authorize(principal);
        LocalDateTime checkedAt = now();
        var record = requests.lockByKey(actor, claim.request().getKeyHash());
        if (record == null || !record.getId().equals(claim.request().getId()) || !"PROCESSING".equals(record.getState())
                || !Objects.equals(record.getLeaseToken(), claim.request().getLeaseToken()) || record.getLeaseUntil() == null
                || !record.getLeaseUntil().isAfter(checkedAt) || !record.getExpiresAt().isAfter(checkedAt)) throw inProgress(1);
        var result = restoreContent(claim.contentId(), claim.expectedVersion(), actor, requestId);
        if (requests.complete(record.getId(), record.getLeaseToken(), result.id(), json.writeValueAsString(result), now()) != 1) throw inProgress(1);
        return result;
    }

    @Transactional
    public void release(Claim claim) { requests.release(claim.request().getId(), claim.request().getLeaseToken()); }

    private ContentWorkspace replay(ContentRestorationRequestRecord record, String actor, String contentId, int expectedVersion) {
        if (!contentId.equals(record.getContentId()) || !actor.equals(writes.findAuthor(contentId))) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        ContentWorkspace result;
        try { result = json.readValue(record.getResultSnapshot(), ContentWorkspace.class); }
        catch (RuntimeException failure) { throw unavailable(); }
        if (result == null || !actor.equals(result.authorId()) || !contentId.equals(result.id()) || result.kind() == null
                || result.version() != (long) expectedVersion + 1 || !"ACTIVE".equals(result.lifecycle()) || result.deletedAt() != null || result.activeReviewId() != null
                || result.publicationStatus() == null || !Set.of("UNPUBLISHED", "WITHDRAWN", "TAKEN_DOWN").contains(result.publicationStatus())
                || "WITHDRAWN".equals(result.publicationStatus()) && result.publishedRevision() == null
                || "UNPUBLISHED".equals(result.publicationStatus()) && result.publishedRevision() != null
                || result.workingRevision() != null && (!contentId.equals(result.workingRevision().contentId()) || !"DRAFT".equals(result.workingRevision().status()))
                || result.publishedRevision() != null && (!contentId.equals(result.publishedRevision().contentId()) || !"APPROVED".equals(result.publishedRevision().status()))) throw unavailable();
        return result;
    }

    private ContentWorkspace restoreContent(String contentId, int expectedVersion, String actor, String requestId) {
        var content = locks.lockOwned(contentId, actor);
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!"DELETED".equals(content.lifecycle())) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        if (content.version() != expectedVersion || content.version() == Integer.MAX_VALUE) throw conflict(content.version());
        if (content.activeReviewId() != null || writes.lockPendingId(contentId) != null) throw unavailable();
        var working = writes.lockWorking(contentId);
        if (!Objects.equals(content.workingRevisionId(), working == null ? null : working.id()) || working != null && !"DRAFT".equals(working.status())) throw unavailable();
        var now = now();
        if (writes.restore(contentId, actor, content.version(), now) != 1) throw conflict(content.version());
        var result = workspaces.workspace(reads.findOwned(contentId, actor));
        persist(content, result, actor, requestId, now);
        return result;
    }

    private void persist(ContentRow before, ContentWorkspace result, String actor, String requestId, LocalDateTime now) {
        if (writes.insertAudit(new ContentRestorationAudit(id(), actor, before.id(), before.publishedRevisionId(), before.publicationStatus(),
                result.publicationStatus(), before.version(), result.version(), requestId, now)) != 1) throw unavailable();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventVersion", 1); payload.put("actorId", actor); payload.put("contentId", result.id());
        payload.put("lifecycle", "ACTIVE"); payload.put("publicationStatus", result.publicationStatus()); payload.put("publishedRevisionId", before.publishedRevisionId());
        payload.put("contentVersion", result.version()); payload.put("workingRevisionId", before.workingRevisionId()); payload.put("activeReviewId", null);
        payload.put("requestId", requestId); payload.put("occurredAt", now.toInstant(ZoneOffset.UTC));
        if (writes.insertEvent(id(), result.id(), before.publishedRevisionId(), result.version(), json.writeValueAsString(payload), now) != 1) throw unavailable();
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
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容恢复关联数据暂不可用"); }
    private BusinessException inProgress(long seconds) { return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同恢复请求正在处理", null, seconds, List.of()); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
}
