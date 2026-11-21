package com.freecontent.community.user.dto;

import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.model.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"id", "displayName", "maskedEmail", "emailVerified", "roles", "accountStatus", "version", "createdAt"})
public record AdminUser(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        String displayName,
        String maskedEmail,
        boolean emailVerified,
        List<UserRole> roles,
        UserAccount.Status accountStatus,
        @Schema(minimum = "1", maximum = "2147483647") int version,
        Instant createdAt) {
    public AdminUser {
        roles = List.copyOf(roles);
    }
}
