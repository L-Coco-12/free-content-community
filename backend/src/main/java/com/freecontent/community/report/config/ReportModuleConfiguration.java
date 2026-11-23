package com.freecontent.community.report.config;

import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReportSubmissionProperties.class, ReportQueryProperties.class,
        AdminReportQueryProperties.class, ReportAttachmentAccessProperties.class, ReportDecisionProperties.class})
public class ReportModuleConfiguration {
    @Bean
    OpenApiCustomizer reportDescriptionRequirement() {
        return api -> {
            var components = api.getComponents();
            if (components == null || components.getSchemas() == null) return;
            var report = components.getSchemas().get("Report");
            if (report == null) return;

            var otherReason = new ObjectSchema();
            otherReason.addProperty("reasonCode", new StringSchema()._const("OTHER"));
            otherReason.addRequiredItem("reasonCode");
            var requireDescription = new ObjectSchema();
            requireDescription.addProperty("description", new StringSchema().minLength(1));
            requireDescription.addRequiredItem("description");
            var rule = new Schema<>();
            rule.setIf(otherReason);
            rule.setThen(requireDescription);
            report.setAllOf(List.of(rule));
        };
    }

    @Bean
    OpenApiCustomizer reportDecisionActionRequirement() {
        return api -> {
            var components = api.getComponents();
            if (components == null || components.getSchemas() == null) return;
            var request = components.getSchemas().get("ReportDecisionRequest");
            if (request == null) return;

            var resolve = new ObjectSchema();
            resolve.addProperty("decision", new StringSchema()._const("RESOLVE"));
            resolve.addRequiredItem("decision");
            var requireAction = new ObjectSchema();
            requireAction.addRequiredItem("moderationActionId");
            var rule = new Schema<>();
            rule.setIf(resolve);
            rule.setThen(requireAction);
            request.setAllOf(List.of(rule));
        };
    }
}
