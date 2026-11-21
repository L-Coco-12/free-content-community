package com.freecontent.community.notification.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(requiredProperties = {"notificationIds"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BatchReadRequest(
        @NotEmpty @Size(max = 100)
        @ArraySchema(minItems = 1, maxItems = 100, uniqueItems = true,
                schema = @Schema(type = "string", minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
        List<@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String> notificationIds) { }
