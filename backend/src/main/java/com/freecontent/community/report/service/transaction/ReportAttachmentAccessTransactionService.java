package com.freecontent.community.report.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.media.storage.MediaObjectReader.ObjectReference;
import com.freecontent.community.media.storage.MediaReadSigner;
import com.freecontent.community.report.config.ReportAttachmentAccessProperties;
import com.freecontent.community.report.mapper.ReportAttachmentAccessMapper;
import com.freecontent.community.report.mapper.ReportAttachmentAccessMapper.AccessAudit;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportAttachmentAccessTransactionService {
    private final AdminReportReadAuthorization authorization;
    private final ReportAttachmentAccessMapper attachments;
    private final MediaAssetMapper assets;
    private final StorageProperties storage;
    private final MediaReadSigner signer;
    private final ReportAttachmentAccessProperties properties;
    private final Clock clock;

    public ReportAttachmentAccessTransactionService(AdminReportReadAuthorization authorization,
            ReportAttachmentAccessMapper attachments, MediaAssetMapper assets, StorageProperties storage,
            MediaReadSigner signer, ReportAttachmentAccessProperties properties, Clock clock) {
        this.authorization = authorization;
        this.attachments = attachments;
        this.assets = assets;
        this.storage = storage;
        this.signer = signer;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ObjectReference prepare(String reportId, String assetId, SessionPrincipal principal) {
        return requireBoundObject(reportId, assetId, principal);
    }

    @Transactional
    public MediaAccess finish(String reportId, String assetId, SessionPrincipal principal,
                              ObjectReference checked, String requestId) {
        var current = requireBoundObject(reportId, assetId, principal);
        if (!current.equals(checked)) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "举报附件读取绑定已变化，请重新申请");
        }
        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var result = signer.sign(current, issued, properties.readUrlTtlSeconds());
        var audit = new AccessAudit(UUID.randomUUID().toString().replace("-", ""), principal.user().id(),
                reportId, assetId, requestId, LocalDateTime.ofInstant(issued, ZoneOffset.UTC),
                LocalDateTime.ofInstant(result.expiresAt(), ZoneOffset.UTC));
        if (attachments.insertAudit(audit) != 1) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "举报附件访问审计暂不可用");
        }
        return result;
    }

    private ObjectReference requireBoundObject(String reportId, String assetId, SessionPrincipal principal) {
        authorization.requirePermission(principal);
        String reporter = attachments.lockAttachmentReporter(reportId, assetId);
        if (reporter == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var asset = assets.findOwnedForShare(assetId, reporter);
        if (asset == null || !"REPORT_ATTACHMENT".equals(asset.getScene())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!"READY".equals(asset.getState())) throw new BusinessException(ErrorCode.MEDIA_NOT_READY);
        return ObjectReference.fromAsset(assetId, reporter, asset, storage);
    }
}
