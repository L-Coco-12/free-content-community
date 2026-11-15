package com.freecontent.community.content.workspace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.publication-republish")
public record ContentRepublicationProperties(int ipPerMinuteLimit, int userPerMinuteLimit, String redisKeyPrefix) {
    public ContentRepublicationProperties {
        if (ipPerMinuteLimit < 1 || userPerMinuteLimit < 1 || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) throw new IllegalArgumentException("重新公开限流配置不合法");
    }
}
