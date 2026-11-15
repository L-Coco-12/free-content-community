package com.freecontent.community.content.moderation.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.dto.WorkspacePage;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.moderation.mapper.AdminContentQueryMapper;
import com.freecontent.community.content.service.read.ContentWorkspaceReader;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import java.math.BigInteger;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminContentQueryTransactionService {
    private final AdminContentQueryMapper contents;
    private final AccountAccessService accounts;
    private final ContentWorkspaceReader reader;
    public AdminContentQueryTransactionService(AdminContentQueryMapper contents, AccountAccessService accounts, ContentWorkspaceReader reader) {
        this.contents = contents; this.accounts = accounts; this.reader = reader;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkspacePage list(BigInteger page, int pageSize, String author, String lifecycle, String publication, SessionPrincipal principal) {
        authorize(principal);
        long total = contents.count(author, lifecycle, publication);
        BigInteger offset = page.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(pageSize));
        if (offset.compareTo(BigInteger.valueOf(total)) >= 0) return new WorkspacePage(List.of(), page, pageSize, total);
        return new WorkspacePage(reader.workspaces(contents.page(author, lifecycle, publication, offset.longValueExact(), pageSize)), page, pageSize, total);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentWorkspace read(String contentId, SessionPrincipal principal) {
        authorize(principal);
        var content = reader.findContent(contentId);
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return reader.workspace(content);
    }
    private void authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        var roles = accounts.findRolesForShare(account.id());
        if (!account.emailVerified() || roles.stream().noneMatch(role -> role == UserRole.MODERATOR || role == UserRole.ADMIN))
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要审核员或管理员的content:moderate:read权限");
    }
}
