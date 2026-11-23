package com.freecontent.community.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.report.attachment-access")
public record ReportAttachmentAccessProperties(int readUrlTtlSeconds, int ipPerMinuteLimit, int userPerMinuteLimit,
                                               String redisKeyPrefix) {
    public ReportAttachmentAccessProperties {
        if (readUrlTtlSeconds < 1 || readUrlTtlSeconds > 604800 || ipPerMinuteLimit < 1 || userPerMinuteLimit < 1
                || redisKeyPrefix == null || redisKeyPrefix.isBlank() || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("举报附件访问配置不合法");
        }
    }
}
