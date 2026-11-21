package com.freecontent.community.user.dto;

import com.freecontent.community.user.model.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"code", "name", "permissions"})
public record RoleDefinition(UserRole code, String name, List<String> permissions) {
    public RoleDefinition {
        permissions = List.copyOf(permissions);
    }
}
