package com.freecontent.community.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.report.query")
public record ReportQueryProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public ReportQueryProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("本人举报查询限流配置不合法");
        }
    }
}
