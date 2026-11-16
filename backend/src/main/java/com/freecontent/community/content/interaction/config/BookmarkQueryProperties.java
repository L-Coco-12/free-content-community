package com.freecontent.community.content.interaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.bookmark-query")
public record BookmarkQueryProperties(int ipPerMinuteLimit,int userPerMinuteLimit,String redisKeyPrefix) {
    public BookmarkQueryProperties {
        if(ipPerMinuteLimit<1 || userPerMinuteLimit<1 || redisKeyPrefix==null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}"))throw new IllegalArgumentException("收藏查询限流配置不合法");
    }
}
