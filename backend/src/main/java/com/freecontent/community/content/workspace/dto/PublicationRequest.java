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
@Schema(name = "PublicationRequest", requiredProperties = {"expectedVersion", "revisionId"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class PublicationRequest {
    @NotNull @Positive @Max(Integer.MAX_VALUE) private BigInteger expectedVersion;
    @NotNull @Size(min = 1, max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") private String revisionId;

    @Schema(type = "integer", implementation = Integer.class, minimum = "1", maximum = "2147483647")
    public Integer getExpectedVersion() { return expectedVersion == null ? null : expectedVersion.intValueExact(); }
    @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$", description = "最近一次审核通过的修订ID")
    public String getRevisionId() { return revisionId; }

    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) expectedVersion = null;
        else { if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数"); expectedVersion = value.bigIntegerValue(); }
    }
    @JsonSetter(value = "revisionId", nulls = Nulls.SET)
    public void setRevisionId(String value) { revisionId = value; }
}
