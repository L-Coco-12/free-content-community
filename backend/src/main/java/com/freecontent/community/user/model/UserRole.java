package com.freecontent.community.user.model;

import java.util.List;
import java.util.stream.Stream;

public enum UserRole {
    USER("普通用户", List.of("content:write:self", "statistics:read:self")),
    MODERATOR("审核员", USER, List.of(
            "comment:hide", "comment:moderate:read", "content:moderate:read", "content:take-down",
            "moderation:read", "report:decide", "report:read", "review:decide", "review:read")),
    ADMIN("管理员", MODERATOR, List.of(
            "audit:read", "role:manage", "statistics:read:platform", "tag:manage",
            "task:read", "task:retry", "user:read", "user:restrict"));

    private final String displayName;
    private final List<String> permissions;

    UserRole(String displayName, List<String> permissions) {
        this.displayName = displayName;
        this.permissions = List.copyOf(permissions);
    }

    UserRole(String displayName, UserRole base, List<String> additionalPermissions) {
        this(displayName, Stream.concat(base.permissions.stream(), additionalPermissions.stream()).toList());
    }

    public String displayName() {
        return displayName;
    }

    /** 契约中的角色权限目录，实际访问仍由各接口检查角色和业务条件。 */
    public List<String> permissions() {
        return permissions;
    }
}
