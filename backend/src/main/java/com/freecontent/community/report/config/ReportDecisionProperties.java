package com.freecontent.community.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.report.decision")
public record ReportDecisionProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public ReportDecisionProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("举报处理限流配置不合法");
        }
    }
}
