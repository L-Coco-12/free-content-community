package com.freecontent.community.content.workspace.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.mapper.ContentLockMapper;
import com.freecontent.community.content.mapper.ContentReadMapper;
import com.freecontent.community.content.model.persistence.PublicationRepublishAudit;
import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.content.service.read.ContentWorkspaceReader;
import com.freecontent.community.content.service.read.PublicContentVisibilityReader;
import com.freecontent.community.content.workspace.dto.PublicationRequest;
import com.freecontent.community.content.workspace.mapper.ContentRepublicationMapper;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentRepublicationTransactionService {
    private final AccountAccessService accounts;
    private final ContentLockMapper locks;
    private final ContentReadMapper reads;
    private final ContentWorkspaceReader workspaces;
    private final ContentRepublicationMapper writes;
    private final PublicContentVisibilityReader visibility;
    private final Clock clock;
    private final JsonMapper json;
    public ContentRepublicationTransactionService(AccountAccessService accounts, ContentLockMapper locks, ContentReadMapper reads,
            ContentWorkspaceReader workspaces, ContentRepublicationMapper writes, Clock clock, PublicContentVisibilityReader visibility, JsonMapper json) {
        this.accounts = accounts; this.locks = locks; this.reads = reads; this.workspaces = workspaces; this.writes = writes; this.clock = clock;
        this.visibility = visibility;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContentWorkspace republish(String contentId, PublicationRequest input, SessionPrincipal principal, String requestId) {
        String actor = authorize(principal);
        var content = locks.lockOwned(contentId, actor);
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!"ACTIVE".equals(content.lifecycle())) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        if ("TAKEN_DOWN".equals(content.publicationStatus())) throw new BusinessException(ErrorCode.REMEDIATION_REQUIRED);
        if (!"WITHDRAWN".equals(content.publicationStatus()) && !"PUBLISHED".equals(content.publicationStatus())) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        var revision = locks.lockRevision(input.getRevisionId(), contentId);
        if (revision == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!"APPROVED".equals(revision.status()) || !revision.id().equals(writes.lockLatestApproved(contentId))) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        var times = writes.publicationTimes(contentId);
        if (content.publishedRevisionId() == null || times == null || times.firstPublishedAt() == null || times.publishedAt() == null
                || times.firstPublishedAt().isAfter(times.publishedAt())) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "历史发布数据暂不可用");
        visibility.requireVisible(content, revision, times.firstPublishedAt(), times.publishedAt());
        if ("PUBLISHED".equals(content.publicationStatus()) && revision.id().equals(content.publishedRevisionId())) return workspaces.workspace(content);
        if (!"WITHDRAWN".equals(content.publicationStatus())) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        if (content.version() != input.getExpectedVersion() || content.version() == Integer.MAX_VALUE) throw conflict(content.version());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (writes.republish(contentId, actor, content.version(), revision.id(), now) != 1) throw conflict(content.version());
        persist(content, revision.id(), actor, requestId, times.firstPublishedAt(), now);
        return workspaces.workspace(reads.findOwned(contentId, actor));
    }

    private void persist(ContentRow content, String revision, String actor, String requestId, LocalDateTime firstPublishedAt, LocalDateTime now) {
        var audit = new PublicationRepublishAudit(id(), actor, content.id(), revision, content.version(), content.version() + 1, requestId, now);
        if (writes.insertAudit(audit) != 1) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "重新公开审计暂不可用");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventVersion", 1); payload.put("actorId", actor); payload.put("contentId", content.id());
        payload.put("publishedRevisionId", revision); payload.put("publicationStatus", "PUBLISHED");
        payload.put("contentVersion", content.version() + 1); payload.put("workingRevisionId", content.workingRevisionId());
        payload.put("activeReviewId", content.activeReviewId()); payload.put("firstPublishedAt", firstPublishedAt.toInstant(ZoneOffset.UTC));
        payload.put("publishedAt", now.toInstant(ZoneOffset.UTC)); payload.put("requestId", requestId); payload.put("occurredAt", now.toInstant(ZoneOffset.UTC));
        if (writes.insertEvent(id(), content.id(), revision, content.version() + 1, json.writeValueAsString(payload), now) != 1)
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "重新公开事件暂不可用");
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    private String authorize(SessionPrincipal principal) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        var account = accounts.findByIdForUpdate(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified() || !accounts.findRolesForShare(account.id()).contains(UserRole.USER)) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return account.id();
    }

    private BusinessException conflict(int version) { return new BusinessException(ErrorCode.CONTENT_VERSION_CONFLICT, "内容已变化，请重新读取工作区", version); }
}
