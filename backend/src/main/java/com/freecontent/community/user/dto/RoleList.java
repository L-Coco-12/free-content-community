package com.freecontent.community.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"items"})
public record RoleList(List<RoleDefinition> items) {
    public RoleList {
        items = List.copyOf(items);
    }
}
