package com.freecontent.community.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.admin-query")
public record AdminUserQueryProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public AdminUserQueryProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("后台用户查询限流配置不合法");
        }
    }
}
