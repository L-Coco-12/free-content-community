package com.freecontent.community.report.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse.Violation;
import com.freecontent.community.content.moderation.mapper.ModerationActionQueryMapper;
import com.freecontent.community.notification.service.ReportResultNotificationWriter;
import com.freecontent.community.report.dto.AdminReport;
import com.freecontent.community.report.dto.ReportDecisionRequest.Decision;
import com.freecontent.community.report.dto.ReportRecord.State;
import com.freecontent.community.report.dto.ReportSubmissionRequest.TargetType;
import com.freecontent.community.report.mapper.AdminReportQueryMapper;
import com.freecontent.community.report.mapper.AdminReportQueryMapper.AttachmentRow;
import com.freecontent.community.report.mapper.AdminReportQueryMapper.ReportRow;
import com.freecontent.community.report.mapper.ReportDecisionMapper;
import com.freecontent.community.report.mapper.ReportDecisionMapper.DecisionAudit;
import com.freecontent.community.report.mapper.ReportDecisionRequestMapper;
import com.freecontent.community.report.mapper.ReportDecisionRequestMapper.RequestRow;
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
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReportDecisionTransactionService {
    private static final String PERMISSION = "report:decide";
    private static final long LEASE_SECONDS = 60;
    private final AccountAccessService accounts;
    private final ReportDecisionMapper decisions;
    private final ReportDecisionRequestMapper requests;
    private final AdminReportQueryMapper reports;
    private final ModerationActionQueryMapper actions;
    private final ReportResultNotificationWriter notifications;
    private final JsonMapper json;
    private final Clock clock;

    public ReportDecisionTransactionService(AccountAccessService accounts, ReportDecisionMapper decisions,
            ReportDecisionRequestMapper requests, AdminReportQueryMapper reports, ModerationActionQueryMapper actions,
            ReportResultNotificationWriter notifications, JsonMapper json, Clock clock) {
        this.accounts = accounts;
        this.decisions = decisions;
        this.requests = requests;
        this.reports = reports;
        this.actions = actions;
        this.notifications = notifications;
        this.json = json;
        this.clock = clock;
    }

    public record Input(String reportId, int expectedVersion, Decision decision, String reason, String actionId) {
        State resultingState() { return decision == Decision.RESOLVE ? State.RESOLVED : State.DISMISSED; }
    }
    public record Claim(String operatorId, String keyHash, String leaseToken, Input input, AdminReport replay) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(Input input, String keyHash, String fingerprint, SessionPrincipal principal) {
        String operator = principal.user().id();
        var observed = requests.find(operator, keyHash);
        var now = now();
        // 活动租约已提交，重试可先返回处理中，避免等待执行事务的行锁。
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
    public AdminReport finish(Claim claim, SessionPrincipal principal, String requestId) {
        String operator = authorize(principal, false);
        if (!operator.equals(claim.operatorId())) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        var row = requests.lock(operator, claim.keyHash());
        var checkedAt = now();
        if (row == null || !claim.leaseToken().equals(row.leaseToken())
                || !row.hasLiveLease(checkedAt) || !row.expiresAt().isAfter(checkedAt)) throw inProgress(1);

        Input input = claim.input();
        var report = decisions.lockReport(input.reportId());
        if (report == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (report.version() != input.expectedVersion() || report.version() == Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "举报版本已变化，请重新读取", report.version());
        }
        if (report.state() != State.OPEN) throw new BusinessException(ErrorCode.REPORT_STATE_CONFLICT);
        requireMatchingAction(input.actionId(), report);

        var changedAt = now();
        if (decisions.close(report.id(), report.version(), input.resultingState(), input.reason(), changedAt) != 1) {
            throw unavailable();
        }
        var audit = new DecisionAudit(id(), report.id(), operator, input.decision(), input.reason(),
                report.targetType() == TargetType.CONTENT ? input.actionId() : null,
                report.targetType() == TargetType.COMMENT ? input.actionId() : null,
                report.version(), report.version() + 1, requestId, changedAt);
        if (decisions.insertAudit(audit) != 1) throw unavailable();
        notifications.create(report.reporterId(), report.id(), input.decision() == Decision.RESOLVE, changedAt);

        var closed = reports.findById(report.id());
        if (closed == null) throw unavailable();
        var attachmentIds = reports.attachments(List.of(report.id())).stream().map(AttachmentRow::assetId).toList();
        var result = AdminReportQueryTransactionService.view(closed, attachmentIds);
        if (requests.complete(operator, claim.keyHash(), claim.leaseToken(), audit.id(),
                json.writeValueAsString(result), now()) != 1) throw inProgress(1);
        return result;
    }

    @Transactional
    public void release(Claim claim) {
        requests.release(claim.operatorId(), claim.keyHash(), claim.leaseToken());
    }

    private void requireMatchingAction(String actionId, ReportRow report) {
        if (actionId == null) return;
        var records = actions.findById(actionId);
        if (records.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (records.size() != 1) throw unavailable();
        var action = records.get(0);
        if (!report.targetType().name().equals(action.targetType()) || !report.targetId().equals(action.targetId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "处置记录与举报目标不匹配", null, null,
                    List.of(Violation.body("moderationActionId", "必须关联同一举报目标的已执行处置")));
        }
    }

    private Claim existing(RequestRow row, String operator, String keyHash, String fingerprint, Input input, LocalDateTime now) {
        if (!row.fingerprint().equals(fingerprint)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        if (row.isComplete()) {
            AdminReport replay;
            try {
                replay = json.readValue(row.resultSnapshot(), AdminReport.class);
            } catch (RuntimeException failure) {
                throw unavailable();
            }
            if (replay == null || !input.reportId().equals(replay.id()) || replay.state() != input.resultingState()
                    || replay.version() != input.expectedVersion() + 1 || !input.reason().equals(replay.resolution())) {
                throw unavailable();
            }
            return new Claim(operator, keyHash, null, input, replay);
        }
        if (row.hasLiveLease(now)) {
            long millis = Duration.between(now, row.leaseUntil()).toMillis();
            throw inProgress(Math.max(1, (millis + 999) / 1000));
        }
        return null;
    }

    private String authorize(SessionPrincipal principal, boolean exclusive) {
        String operator = principal.user().id();
        var account = exclusive ? accounts.findByIdForUpdate(operator) : accounts.findByIdForShare(operator);
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified() || accounts.findRolesForShare(operator).stream()
                .noneMatch(role -> role.permissions().contains(PERMISSION))) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要审核员或管理员的 report:decide 权限");
        }
        return operator;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static BusinessException inProgress(long seconds) {
        return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同举报处理请求正在处理中", null, seconds, List.of());
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "举报处理数据服务暂不可用");
    }
}
