package com.freecontent.community.report.dto;

import com.freecontent.community.report.dto.ReportRecord.State;
import com.freecontent.community.report.dto.ReportSubmissionRequest.ReasonCode;
import com.freecontent.community.report.dto.ReportSubmissionRequest.TargetType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"id", "targetType", "targetId", "reasonCode", "description", "state", "version",
        "createdAt", "resolvedAt", "resolution", "reporterId", "attachmentAssetIds"})
public record AdminReport(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id,
        TargetType targetType,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String targetId,
        ReasonCode reasonCode, String description, State state,
        @Schema(minimum = "1", maximum = "2147483647") int version,
        Instant createdAt,
        @Schema(types = {"string", "null"}, format = "date-time") Instant resolvedAt,
        @Schema(types = {"string", "null"}, maxLength = 1000) String resolution,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String reporterId,
        @ArraySchema(maxItems = 3, schema = @Schema(type = "string", minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
        List<String> attachmentAssetIds) {
    public AdminReport {
        attachmentAssetIds = List.copyOf(attachmentAssetIds);
    }
}
