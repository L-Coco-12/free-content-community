package com.freecontent.community.content.workspace.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.mapper.ContentLockMapper;
import com.freecontent.community.content.mapper.ContentReadMapper;
import com.freecontent.community.content.model.ContentDraftData;
import com.freecontent.community.content.model.persistence.DraftRevisionInsert;
import com.freecontent.community.content.service.read.ContentWorkspaceReader;
import com.freecontent.community.content.validation.ContentDraftValidator;
import com.freecontent.community.content.validation.ContentReferenceValidator;
import com.freecontent.community.content.workspace.dto.CreateDraftRequest;
import com.freecontent.community.content.workspace.entity.ContentDraftCreationRequestRecord;
import com.freecontent.community.content.workspace.mapper.ContentDraftCreationMapper;
import com.freecontent.community.content.workspace.mapper.ContentDraftCreationRequestMapper;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 租约领取与业务完成分开提交；完成事务中原子复制修订、引用、内容版本及成功快照。 */
@Service
public class ContentDraftCreationTransactionService {
    private static final int LEASE_SECONDS = 60;
    private static final int IDEMPOTENCY_HOURS = 24;
    private static final Set<String> SOURCES = Set.of("APPROVED", "REJECTED", "CANCELLED");
    private final ContentDraftCreationMapper writes;
    private final ContentLockMapper locks;
    private final ContentReadMapper reads;
    private final ContentWorkspaceReader workspaces;
    private final AccountAccessService accounts;
    private final ContentDraftValidator validator;
    private final ContentReferenceValidator references;
    private final Clock clock;
    private final ContentDraftCreationRequestMapper requests;
    private final JsonMapper json;

    public ContentDraftCreationTransactionService(ContentDraftCreationMapper writes, ContentLockMapper locks, ContentReadMapper reads,
            ContentWorkspaceReader workspaces, AccountAccessService accounts, ContentDraftValidator validator,
            ContentReferenceValidator references, Clock clock, ContentDraftCreationRequestMapper requests, JsonMapper json) {
        this.writes = writes; this.locks = locks; this.reads = reads; this.workspaces = workspaces;
        this.accounts = accounts; this.validator = validator; this.references = references; this.clock = clock;
        this.requests = requests; this.json = json;
    }

    public record Selection(int expectedVersion, String sourceRevisionId) { }
    public record Claim(ContentDraftCreationRequestRecord request, String contentId, Selection input, ContentWorkspace replay) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(String contentId, CreateDraftRequest input, String keyHash, String fingerprint, SessionPrincipal principal) {
        String actor = authorize(principal);
        var selection = new Selection(input.getExpectedVersion(), input.getSourceRevisionId());
        LocalDateTime now = now();
        var record = requests.lockByKey(actor, keyHash);
        if (record != null && !record.getExpiresAt().isAfter(now)) { requests.deleteById(record.getId()); record = null; }
        if (record != null) {
            if (!Objects.equals(record.getFingerprint(), fingerprint)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            if ("SUCCEEDED".equals(record.getState())) return new Claim(record, contentId, selection, replay(record, actor));
            if (record.getLeaseUntil() != null && record.getLeaseUntil().isAfter(now)) {
                throw inProgress(Math.max(1, (Duration.between(now, record.getLeaseUntil()).toMillis() + 999) / 1000));
            }
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(LEASE_SECONDS));
            if (requests.renew(record.getId(), record.getLeaseToken(), record.getLeaseUntil()) != 1) throw inProgress(1);
        } else {
            record = new ContentDraftCreationRequestRecord();
            record.setId(id()); record.setCreatedBy(actor); record.setKeyHash(keyHash); record.setFingerprint(fingerprint);
            record.setState("PROCESSING"); record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(LEASE_SECONDS));
            record.setCreatedAt(now); record.setExpiresAt(now.plusHours(IDEMPOTENCY_HOURS));
            if (requests.insert(record) != 1) throw unavailable();
        }
        return new Claim(record, contentId, selection, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContentWorkspace finish(Claim claim, SessionPrincipal principal) {
        String actor = authorize(principal);
        LocalDateTime checkedAt = now();
        var record = requests.lockByKey(actor, claim.request().getKeyHash());
        if (record == null || !record.getId().equals(claim.request().getId()) || !"PROCESSING".equals(record.getState())
                || !Objects.equals(record.getLeaseToken(), claim.request().getLeaseToken()) || record.getLeaseUntil() == null
                || !record.getLeaseUntil().isAfter(checkedAt) || !record.getExpiresAt().isAfter(checkedAt)) throw inProgress(1);
        var result = copy(claim.contentId(), claim.input(), actor);
        if (requests.complete(record.getId(), record.getLeaseToken(), result.id(), result.workingRevision().id(),
                json.writeValueAsString(result), now()) != 1) throw inProgress(1);
        return result;
    }

    @Transactional
    public void release(Claim claim) { requests.release(claim.request().getId(), claim.request().getLeaseToken()); }

    private ContentWorkspace replay(ContentDraftCreationRequestRecord record, String actor) {
        if (!actor.equals(writes.findAuthor(record.getContentId()))) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        ContentWorkspace result;
        try { result = json.readValue(record.getResultSnapshot(), ContentWorkspace.class); }
        catch (RuntimeException ex) { throw unavailable(); }
        if (result == null || !actor.equals(result.authorId()) || !record.getContentId().equals(result.id())
                || result.workingRevision() == null || !Objects.equals(record.getRevisionId(), result.workingRevision().id())
                || !result.id().equals(result.workingRevision().contentId()) || !"DRAFT".equals(result.workingRevision().status())) throw unavailable();
        return result;
    }

    private ContentWorkspace copy(String contentId, Selection input, String actor) {
        var content = locks.lockOwned(contentId, actor);
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (content.version() != input.expectedVersion() || content.version() == Integer.MAX_VALUE) throw conflict(content.version());
        if (!"ACTIVE".equals(content.lifecycle())) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        if (content.workingRevisionId() != null || content.activeReviewId() != null || writes.workingCount(contentId) != 0) {
            throw new BusinessException(ErrorCode.DRAFT_ALREADY_EXISTS);
        }
        String sourceId = input.sourceRevisionId() == null ? writes.latestRevisionId(contentId) : input.sourceRevisionId();
        if (sourceId == null) throw unavailable();
        var source = locks.lockRevision(sourceId, contentId);
        if (source == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!SOURCES.contains(source.status())) throw unavailable();
        Integer maximum = writes.maximumNumber(contentId);
        if (maximum == null) throw unavailable();
        if (maximum == Integer.MAX_VALUE) throw conflict(content.version());
        var old = workspaces.frozenRevision(contentId, source.id());
        var data = new ContentDraftData(content.kind(), old.title(), old.bodyMarkdown(), old.coverAssetId(), old.imageAssetIds(), old.videoAssetId(), old.tagIds());
        validator.validate(data);
        references.validate(data, actor);
        LocalDateTime now = now();
        String draft = id();
        if (writes.insertRevision(new DraftRevisionInsert(draft, contentId, maximum + 1, old.title(), old.bodyMarkdown()), now) != 1) throw unavailable();
        int assetCount = old.imageAssetIds().size() + (old.coverAssetId() == null ? 0 : 1) + (old.videoAssetId() == null ? 0 : 1);
        if (writes.copyAssets(source.id(), draft) != assetCount || writes.copyTags(source.id(), draft) != old.tagIds().size()) throw unavailable();
        if (writes.attachDraft(contentId, actor, content.version(), draft, now) != 1) throw conflict(content.version());
        return workspaces.workspace(reads.findOwned(contentId, actor));
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
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "草稿复制关联数据暂不可用"); }
    private BusinessException inProgress(long seconds) { return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同新草稿请求正在处理", null, seconds, List.of()); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
}
