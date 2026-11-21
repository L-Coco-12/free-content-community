package com.freecontent.community.user.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import com.freecontent.community.user.entity.UserAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigInteger;
import tools.jackson.databind.JsonNode;

@Schema(requiredProperties = {"expectedVersion", "status", "reason"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class RestrictionRequest {
    @NotNull @Positive @Max(Integer.MAX_VALUE)
    private BigInteger expectedVersion;
    @NotNull
    private UserAccount.Status status;
    @NotBlank @CodePointLength(min = 1, max = 1000)
    private String reason;

    @Schema(type = "integer", implementation = Integer.class, minimum = "1", maximum = "2147483647")
    public Integer getExpectedVersion() {
        return expectedVersion == null ? null : expectedVersion.intValueExact();
    }

    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) {
            expectedVersion = null;
        } else if (value.isIntegralNumber()) {
            expectedVersion = value.bigIntegerValue();
        } else {
            throw new IllegalArgumentException("版本必须为整数");
        }
    }

    public UserAccount.Status getStatus() { return status; }
    public void setStatus(UserAccount.Status status) { this.status = status; }
    @Schema(minLength = 1, maxLength = 1000)
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
