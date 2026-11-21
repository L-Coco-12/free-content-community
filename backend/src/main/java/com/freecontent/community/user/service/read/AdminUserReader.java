package com.freecontent.community.user.service.read;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.AdminUser;
import com.freecontent.community.user.mapper.AdminUserQueryMapper;
import com.freecontent.community.user.model.UserRole;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 复用后台脱敏投影，由调用方在事务中完成授权。 */
@Component
public class AdminUserReader {
    private final AdminUserQueryMapper users;

    public AdminUserReader(AdminUserQueryMapper users) {
        this.users = users;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdminUser get(String userId) {
        var row = users.findById(userId);
        if (row == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var roles = users.findRolesForUsers(List.of(row.id())).stream()
                .map(AdminUserQueryMapper.RoleRow::roleCode).toList();
        return toAdminUser(row, roles);
    }

    public static AdminUser toAdminUser(AdminUserQueryMapper.UserRow row, List<UserRole> roles) {
        return new AdminUser(row.id(), row.displayName(), row.maskedEmail(), row.emailVerified(), roles,
                row.accountStatus(), row.version(), row.createdAt().toInstant(ZoneOffset.UTC));
    }
}
