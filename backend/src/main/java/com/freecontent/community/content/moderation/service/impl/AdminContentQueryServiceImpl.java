package com.freecontent.community.content.moderation.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.common.pagination.OffsetPageParameters;
import com.freecontent.community.content.dto.WorkspacePage;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.moderation.service.AdminContentQueryService;
import com.freecontent.community.content.moderation.service.transaction.AdminContentQueryTransactionService;
import com.freecontent.community.content.moderation.ratelimit.AdminContentQueryRateLimiter;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.stereotype.Service;

@Service
public class AdminContentQueryServiceImpl implements AdminContentQueryService {
    private final AdminContentQueryTransactionService transactions;
    private final AdminContentQueryRateLimiter limiter;
    public AdminContentQueryServiceImpl(AdminContentQueryTransactionService transactions, AdminContentQueryRateLimiter limiter) { this.transactions = transactions; this.limiter = limiter; }
    @Override
    public WorkspacePage list(String page, String pageSize, String authorId, String lifecycle, String publicationStatus, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        var number = OffsetPageParameters.page(page);
        int size = OffsetPageParameters.pageSize(pageSize);
        if (authorId != null && !authorId.matches("^[A-Za-z0-9_-]{1,64}$")) throw invalid(ErrorCode.VALIDATION_ERROR, "authorId", "仅允许1到64位字母、数字、下划线及短横线");
        String state = enumValue(lifecycle, "lifecycle", Set.of("ACTIVE", "DELETED"));
        String publication = enumValue(publicationStatus, "publicationStatus", Set.of("UNPUBLISHED", "PUBLISHED", "WITHDRAWN", "TAKEN_DOWN"));
        limiter.acquire(principal.user().id(), sourceIp);
        try { return transactions.list(number, size, authorId, state, publication, principal); }
        catch (DataAccessException | TransactionException failure) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "后台内容查询数据服务暂不可用"); }
    }
    @Override
    public ContentWorkspace get(String contentId, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (contentId == null || !contentId.matches("^[A-Za-z0-9_-]{1,64}$")) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "内容ID格式不正确", null, null,
                List.of(new ProblemDetailResponse.Violation("path", "contentId", "仅允许1到64位字母、数字、下划线及短横线")));
        limiter.acquire(principal.user().id(), sourceIp);
        try { return transactions.read(contentId, principal); }
        catch (DataAccessException | TransactionException failure) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "后台内容详情数据服务暂不可用"); }
    }
    private String enumValue(String raw, String field, Set<String> allowed) {
        if (raw != null && !allowed.contains(raw)) throw invalid(ErrorCode.MALFORMED_REQUEST, field, "不支持的枚举值");
        return raw;
    }
    private BusinessException invalid(ErrorCode code, String field, String reason) {
        return new BusinessException(code, "查询参数不合法", null, null, List.of(ProblemDetailResponse.Violation.query(field, reason)));
    }
}
