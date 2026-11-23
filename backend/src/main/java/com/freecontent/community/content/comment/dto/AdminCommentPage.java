package com.freecontent.community.content.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigInteger;
import java.util.List;

@Schema(requiredProperties = {"items", "page", "pageSize", "total"})
public record AdminCommentPage(List<AdminComment> items,
                               @Schema(type = "integer", minimum = "1") BigInteger page,
                               @Schema(minimum = "1", maximum = "100") int pageSize,
                               @Schema(minimum = "0") long total) {
    public AdminCommentPage {
        items = List.copyOf(items);
    }
}
