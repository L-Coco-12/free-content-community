package com.freecontent.community.report.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(name = "Report", requiredProperties = {"targetType", "targetId", "reasonCode"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class ReportSubmissionRequest {
    public enum TargetType { CONTENT, COMMENT }
    public enum ReasonCode { SPAM, ABUSE, COPYRIGHT, OTHER }

    private static final String ID_PATTERN = "^[A-Za-z0-9_-]+$";

    @NotNull
    private TargetType targetType;

    @NotNull @Size(min = 1, max = 64) @Pattern(regexp = ID_PATTERN)
    private String targetId;

    @NotNull
    private ReasonCode reasonCode;

    @CodePointLength(max = 1000)
    private String description = "";

    @Size(max = 3)
    private List<@NotNull @Size(min = 1, max = 64) @Pattern(regexp = ID_PATTERN) String> attachmentAssetIds = List.of();

    public TargetType getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public ReasonCode getReasonCode() { return reasonCode; }

    @Schema(maxLength = 1000, description = "补充说明，OTHER 时必填")
    public String getDescription() { return description; }

    @ArraySchema(maxItems = 3, uniqueItems = true,
            schema = @Schema(type = "string", minLength = 1, maxLength = 64, pattern = ID_PATTERN))
    public List<String> getAttachmentAssetIds() { return attachmentAssetIds; }

    public void setTargetType(TargetType targetType) { this.targetType = targetType; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public void setReasonCode(ReasonCode reasonCode) { this.reasonCode = reasonCode; }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setDescription(String description) { this.description = description; }

    @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    public void setAttachmentAssetIds(List<String> attachmentAssetIds) { this.attachmentAssetIds = attachmentAssetIds; }
}
