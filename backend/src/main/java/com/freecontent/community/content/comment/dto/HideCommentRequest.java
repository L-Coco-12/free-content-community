package com.freecontent.community.content.comment.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigInteger;
import tools.jackson.databind.JsonNode;

@Schema(requiredProperties = {"reason", "expectedVersion"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class HideCommentRequest {
    @NotBlank @CodePointLength(min = 1, max = 1000)
    private String reason;

    @NotNull @Positive @Max(Integer.MAX_VALUE)
    private BigInteger expectedVersion;

    @Schema(minLength = 1, maxLength = 1000)
    public String getReason() { return reason; }

    @Schema(type = "integer", implementation = Integer.class, minimum = "1", maximum = "2147483647")
    public Integer getExpectedVersion() {
        return expectedVersion == null ? null : expectedVersion.intValueExact();
    }

    public void setReason(String reason) { this.reason = reason; }

    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) {
            expectedVersion = null;
            return;
        }
        if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数");
        expectedVersion = value.bigIntegerValue();
    }
}
