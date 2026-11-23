package com.freecontent.community.content.comment.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(requiredProperties = {"body"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class CreateCommentRequest {
    @NotNull
    @CodePointLength(min = 1, max = 2000)
    private String body;

    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    private String replyToCommentId;

    @Schema(minLength = 1, maxLength = 2000, description = "纯文本评论，去除首尾空白后不能为空")
    public String getBody() { return body; }

    @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$", description = "省略时创建顶级评论")
    public String getReplyToCommentId() { return replyToCommentId; }

    public void setBody(String body) { this.body = body; }

    @JsonSetter(value = "replyToCommentId", nulls = Nulls.FAIL)
    public void setReplyToCommentId(String replyToCommentId) { this.replyToCommentId = replyToCommentId; }
}
