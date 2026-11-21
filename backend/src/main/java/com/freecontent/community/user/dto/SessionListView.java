package com.freecontent.community.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name="SessionList",requiredProperties={"items"})
public record SessionListView(List<SessionView> items) {
    public SessionListView { items=List.copyOf(items); }
}
