package com.freecontent.community.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.session-revocation")
public record SessionRevocationProperties(int ipPerMinuteLimit,int userPerMinuteLimit,String redisKeyPrefix) {
    public SessionRevocationProperties {
        if(ipPerMinuteLimit<1 || userPerMinuteLimit<1 || redisKeyPrefix==null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}"))throw new IllegalArgumentException("本人会话撤销限流配置不合法");
    }
}
