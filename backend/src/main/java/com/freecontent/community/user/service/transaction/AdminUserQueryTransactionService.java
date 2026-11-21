package com.freecontent.community.user.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.dto.AdminUserPage;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.AdminUserQueryMapper;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.service.read.AdminUserReader;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserQueryTransactionService {
    private final AccountAccessService accounts;
    private final AdminUserQueryMapper users;
    private final AdminUserReader reader;

    public AdminUserQueryTransactionService(AccountAccessService accounts, AdminUserQueryMapper users, AdminUserReader reader) {
        this.accounts = accounts;
        this.users = users;
        this.reader = reader;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminUser get(String userId, SessionPrincipal principal) {
        authorize(principal);
        return reader.get(userId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminUserPage list(BigInteger page, int pageSize, String userId, UserAccount.Status status,
                              SessionPrincipal principal) {
        authorize(principal);
        long total = users.count(userId, status);
        BigInteger offset = page.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(pageSize));
        if (offset.compareTo(BigInteger.valueOf(total)) >= 0) {
            return new AdminUserPage(List.of(), page, pageSize, total);
        }

        var rows = users.page(userId, status, offset.longValueExact(), pageSize);
        if (rows.isEmpty()) {
            return new AdminUserPage(List.of(), page, pageSize, total);
        }
        var ids = rows.stream().map(AdminUserQueryMapper.UserRow::id).toList();
        var roles = users.findRolesForUsers(ids).stream().collect(Collectors.groupingBy(
                AdminUserQueryMapper.RoleRow::userId,
                Collectors.mapping(AdminUserQueryMapper.RoleRow::roleCode, Collectors.toList())));
        var items = rows.stream().map(row -> AdminUserReader.toAdminUser(row, roles.getOrDefault(row.id(), List.of()))).toList();
        return new AdminUserPage(items, page, pageSize, total);
    }

    private void authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        }
        if (!account.emailVerified() || !accounts.findRolesForShare(account.id()).contains(UserRole.ADMIN)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要管理员的user:read权限");
        }
    }
}
