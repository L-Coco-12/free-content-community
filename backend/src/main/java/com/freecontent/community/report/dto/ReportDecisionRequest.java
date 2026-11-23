package com.freecontent.community.report.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigInteger;
import tools.jackson.databind.JsonNode;

@Schema(requiredProperties = {"expectedVersion", "decision", "reason"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class ReportDecisionRequest {
    public enum Decision { RESOLVE, DISMISS }

    @NotNull @Positive @Max(Integer.MAX_VALUE)
    private BigInteger expectedVersion;

    @NotNull
    private Decision decision;

    @NotBlank @CodePointLength(min = 1, max = 1000)
    private String reason;

    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
    private String moderationActionId;

    @Schema(type = "integer", implementation = Integer.class, minimum = "1", maximum = "2147483647")
    public Integer getExpectedVersion() {
        return expectedVersion == null ? null : expectedVersion.intValueExact();
    }

    public Decision getDecision() { return decision; }

    @Schema(minLength = 1, maxLength = 1000)
    public String getReason() { return reason; }

    @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$", description = "已执行的处置记录 ID，RESOLVE 时必填")
    public String getModerationActionId() { return moderationActionId; }

    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) {
            expectedVersion = null;
            return;
        }
        if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数");
        expectedVersion = value.bigIntegerValue();
    }

    public void setDecision(Decision decision) { this.decision = decision; }
    public void setReason(String reason) { this.reason = reason; }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setModerationActionId(String moderationActionId) { this.moderationActionId = moderationActionId; }
}
