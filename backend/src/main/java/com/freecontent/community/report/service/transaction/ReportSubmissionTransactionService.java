package com.freecontent.community.report.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse.Violation;
import com.freecontent.community.content.comment.mapper.CommentMapper;
import com.freecontent.community.content.service.read.PublicContentVisibilityReader;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.report.dto.ReportRecord;
import com.freecontent.community.report.dto.ReportSubmissionRequest.ReasonCode;
import com.freecontent.community.report.dto.ReportSubmissionRequest.TargetType;
import com.freecontent.community.report.mapper.ReportMapper;
import com.freecontent.community.report.mapper.ReportMapper.Submission;
import com.freecontent.community.report.mapper.ReportSubmissionRequestMapper;
import com.freecontent.community.report.mapper.ReportSubmissionRequestMapper.RequestRow;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportSubmissionTransactionService {
    private static final long LEASE_SECONDS = 60;
    private final AccountAccessService accounts;
    private final ReportSubmissionRequestMapper requests;
    private final ReportMapper reports;
    private final PublicContentVisibilityReader visibility;
    private final CommentMapper comments;
    private final MediaAssetMapper assets;
    private final Clock clock;

    public ReportSubmissionTransactionService(AccountAccessService accounts, ReportSubmissionRequestMapper requests,
                                              ReportMapper reports, PublicContentVisibilityReader visibility,
                                              CommentMapper comments, MediaAssetMapper assets, Clock clock) {
        this.accounts = accounts;
        this.requests = requests;
        this.reports = reports;
        this.visibility = visibility;
        this.comments = comments;
        this.assets = assets;
        this.clock = clock;
    }

    public record Input(TargetType targetType, String targetId, ReasonCode reasonCode, String description,
                        List<String> attachmentAssetIds) { }
    public record Claim(String reporterId, String keyHash, String leaseToken, Input input, ReportRecord replay) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(Input input, String keyHash, String fingerprint, SessionPrincipal principal) {
        String reporter = principal.user().id();
        var observed = requests.find(reporter, keyHash);
        var now = now();
        // 已提交的租约可直接识别正在处理的重试，无需等待写事务释放幂等记录锁。
        if (observed != null && observed.expiresAt().isAfter(now)
                && (observed.isComplete() || observed.hasLiveLease(now))) {
            authorize(principal, false);
            return existing(observed, reporter, keyHash, fingerprint, input, now);
        }

        // 在首次创建幂等记录前锁定账号，避免同一账号的两个请求同时插入同一键。
        authorize(principal, true);
        var row = requests.lock(reporter, keyHash);
        now = now();
        if (row != null && !row.expiresAt().isAfter(now)) {
            if (requests.deleteExpired(reporter, keyHash, now) != 1) throw unavailable();
            row = null;
        }
        if (row != null) {
            var previous = existing(row, reporter, keyHash, fingerprint, input, now);
            if (previous != null) return previous;
        }

        String token = id();
        var until = now.plusSeconds(LEASE_SECONDS);
        int changed = row == null
                ? requests.insert(reporter, keyHash, fingerprint, token, until, now, now.plusHours(24))
                : requests.renew(reporter, keyHash, token, until);
        if (changed != 1) throw unavailable();
        return new Claim(reporter, keyHash, token, input, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReportRecord finish(Claim claim, SessionPrincipal principal) {
        authorize(principal, false);
        String reporter = principal.user().id();
        if (!reporter.equals(claim.reporterId())) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        var row = requests.lock(reporter, claim.keyHash());
        var checkedAt = now();
        if (row == null || !claim.leaseToken().equals(row.leaseToken())
                || !row.hasLiveLease(checkedAt) || !row.expiresAt().isAfter(checkedAt)) {
            throw inProgress(1);
        }

        Input input = claim.input();
        requireVisibleTarget(input);
        validateAttachments(reporter, input.attachmentAssetIds());
        var submission = new Submission(id(), input.targetType(), input.targetId(), input.reasonCode(),
                input.description(), now());
        try {
            if (reports.insert(submission, reporter) != 1) throw unavailable();
        } catch (DuplicateKeyException ex) {
            String cause = ex.getMostSpecificCause().getMessage();
            if (cause != null && cause.contains("uk_report_open_target")) {
                throw new BusinessException(ErrorCode.REPORT_ALREADY_OPEN);
            }
            throw ex;
        }
        var attachments = input.attachmentAssetIds();
        for (int position = 0; position < attachments.size(); position++) {
            if (reports.attach(submission.id(), attachments.get(position), position) != 1) throw unavailable();
        }
        if (requests.complete(reporter, claim.keyHash(), claim.leaseToken(), submission.id(), now()) != 1) {
            throw inProgress(1);
        }
        return created(submission);
    }

    @Transactional
    public void release(Claim claim) {
        requests.release(claim.reporterId(), claim.keyHash(), claim.leaseToken());
    }

    private Claim existing(RequestRow row, String reporter, String keyHash, String fingerprint, Input input, LocalDateTime now) {
        if (!row.fingerprint().equals(fingerprint)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        if (row.isComplete()) {
            var submission = reports.findOwnedSubmission(row.reportId(), reporter);
            if (submission == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            return new Claim(reporter, keyHash, null, input, created(submission));
        }
        if (row.hasLiveLease(now)) {
            long millis = Duration.between(now, row.leaseUntil()).toMillis();
            throw inProgress(Math.max(1, (millis + 999) / 1000));
        }
        return null;
    }

    private void authorize(SessionPrincipal principal, boolean exclusive) {
        String reporter = principal.user().id();
        var account = exclusive ? accounts.findByIdForUpdate(reporter) : accounts.findByIdForShare(reporter);
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified()) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
    }

    private void requireVisibleTarget(Input input) {
        if (input.targetType() == TargetType.CONTENT) {
            visibility.requireCurrentlyVisible(input.targetId());
            return;
        }
        String contentId = comments.findVisibleContentId(input.targetId());
        if (contentId == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        visibility.requireCurrentlyVisible(contentId);
        var comment = comments.lockTarget(contentId, input.targetId());
        if (comment == null || !"VISIBLE".equals(comment.state())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private void validateAttachments(String reporter, List<String> assetIds) {
        for (String assetId : assetIds.stream().sorted().toList()) {
            var asset = assets.findOwnedForShare(assetId, reporter);
            if (asset == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            if (!"REPORT_ATTACHMENT".equals(asset.getScene())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "素材用途与举报附件不匹配", null, null,
                        List.of(new Violation("body", "attachmentAssetIds", "必须引用 REPORT_ATTACHMENT 素材")));
            }
            if (!"READY".equals(asset.getState())) throw new BusinessException(ErrorCode.MEDIA_NOT_READY);
        }
    }

    private static ReportRecord created(Submission submission) {
        // 仅用提交时的字段重建原始回执，后续处理状态由查询接口读取。
        return new ReportRecord(submission.id(), submission.targetType(), submission.targetId(), submission.reasonCode(),
                submission.description(), ReportRecord.State.OPEN, 1, submission.createdAt().toInstant(ZoneOffset.UTC),
                null, null);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "举报提交数据服务暂不可用");
    }

    private static BusinessException inProgress(long retryAfter) {
        return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同举报请求正在处理", null, retryAfter, List.of());
    }
}
