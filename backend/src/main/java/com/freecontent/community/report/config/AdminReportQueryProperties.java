package com.freecontent.community.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.report.admin-query")
public record AdminReportQueryProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public AdminReportQueryProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("运营举报查询限流配置不合法");
        }
    }
}
