package com.freecontent.community.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"items", "nextCursor", "hasMore", "snapshotAt"})
public record UserPage(List<PublicUserView> items, @Schema(types = {"string", "null"}) String nextCursor,
                       boolean hasMore, Instant snapshotAt) {
    public UserPage {
        items = List.copyOf(items);
        if (hasMore ? nextCursor == null || nextCursor.isEmpty() : nextCursor != null) {
            throw new IllegalArgumentException("分页状态与游标不一致");
        }
    }
}
