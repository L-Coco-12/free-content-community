package com.freecontent.community.content.moderation.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigInteger;
import tools.jackson.databind.JsonNode;

@Schema(requiredProperties = {"expectedVersion", "reasonCode", "reason"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class TakeDownRequest {
    public enum ReasonCode { SPAM, ABUSE, COPYRIGHT, OTHER }
    @NotNull @Positive @Max(Integer.MAX_VALUE) private BigInteger expectedVersion;
    @NotNull private ReasonCode reasonCode;
    @NotBlank @CodePointLength(min = 1, max = 1000) private String reason;
    @Schema(type = "integer", implementation = Integer.class, minimum = "1", maximum = "2147483647")
    public Integer getExpectedVersion() { return expectedVersion == null ? null : expectedVersion.intValueExact(); }
    public ReasonCode getReasonCode() { return reasonCode; }
    @Schema(minLength = 1, maxLength = 1000) public String getReason() { return reason; }
    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) expectedVersion = null;
        else { if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数"); expectedVersion = value.bigIntegerValue(); }
    }
    public void setReasonCode(ReasonCode value) { reasonCode = value; }
    public void setReason(String value) { reason = value; }
}
