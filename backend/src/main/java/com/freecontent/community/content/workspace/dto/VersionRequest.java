package com.freecontent.community.content.workspace.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigInteger;
import tools.jackson.databind.JsonNode;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE)
@Schema(name = "VersionRequest", requiredProperties = "expectedVersion", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class VersionRequest {
    @NotNull @Positive @Max(Integer.MAX_VALUE)
    private BigInteger expectedVersion;

    @Schema(type = "integer", implementation = Integer.class, minimum = "1", maximum = "2147483647",
            description = "最近工作区的内容聚合版本")
    public Integer getExpectedVersion() { return expectedVersion == null ? null : expectedVersion.intValueExact(); }
    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) expectedVersion = null;
        else {
            if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数");
            expectedVersion = value.bigIntegerValue();
        }
    }
}
