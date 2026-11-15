package com.freecontent.community.content.workspace.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigInteger;
import tools.jackson.databind.JsonNode;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE)
@Schema(name = "CreateDraftRequest", requiredProperties = "expectedVersion", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class CreateDraftRequest {
    @NotNull @Positive @Max(Integer.MAX_VALUE)
    private BigInteger expectedVersion;
    @Size(min = 1, max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$")
    private String sourceRevisionId;

    @Schema(type = "integer", implementation = Integer.class, minimum = "1", maximum = "2147483647",
            description = "最近工作区的内容聚合版本")
    public Integer getExpectedVersion() { return expectedVersion == null ? null : expectedVersion.intValueExact(); }

    @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description = "省略时从同一内容中修订编号最大的终结修订复制")
    public String getSourceRevisionId() { return sourceRevisionId; }

    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) expectedVersion = null;
        else {
            if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数");
            expectedVersion = value.bigIntegerValue();
        }
    }

    @JsonSetter(value = "sourceRevisionId", nulls = Nulls.SET)
    public void setSourceRevisionId(String value) { sourceRevisionId = value == null ? "" : value; }
}
