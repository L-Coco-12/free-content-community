package com.freecontent.community.content.interaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.bookmark")
public record ContentBookmarkProperties(int ipPerMinuteLimit,int userPerMinuteLimit,String redisKeyPrefix) {
    public ContentBookmarkProperties {
        if(ipPerMinuteLimit<1 || userPerMinuteLimit<1 || redisKeyPrefix==null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}"))throw new IllegalArgumentException("收藏限流配置不合法");
    }
}
