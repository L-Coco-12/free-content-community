package com.freecontent.community.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.report.submission")
public record ReportSubmissionProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public ReportSubmissionProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("举报提交限流配置不合法");
        }
    }
}
