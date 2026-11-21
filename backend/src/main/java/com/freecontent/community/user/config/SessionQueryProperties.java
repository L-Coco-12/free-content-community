package com.freecontent.community.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.session-query")
public record SessionQueryProperties(int ipPerMinuteLimit,int userPerMinuteLimit,String redisKeyPrefix) {
    public SessionQueryProperties {
        if(ipPerMinuteLimit<1 || userPerMinuteLimit<1 || redisKeyPrefix==null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}"))throw new IllegalArgumentException("本人会话查询限流配置不合法");
    }
}
