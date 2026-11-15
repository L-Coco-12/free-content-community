package com.freecontent.community.content.interaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.like")
public record ContentLikeProperties(int ipPerMinuteLimit,int userPerMinuteLimit,String redisKeyPrefix) {
    public ContentLikeProperties {
        if(ipPerMinuteLimit<1 || userPerMinuteLimit<1 || redisKeyPrefix==null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}"))throw new IllegalArgumentException("点赞限流配置不合法");
    }
}
