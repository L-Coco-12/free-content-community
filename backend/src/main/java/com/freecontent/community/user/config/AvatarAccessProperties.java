package com.freecontent.community.user.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.user.avatar-access")
public record AvatarAccessProperties(int readUrlTtlSeconds,int ipPerMinuteLimit,int userPerMinuteLimit,
        String redisKeyPrefix,String publicBaseUrl) {
    public AvatarAccessProperties {
        publicBaseUrl=publicBaseUrl==null?"":publicBaseUrl.strip().replaceAll("/+$","");
        if(readUrlTtlSeconds<1 || readUrlTtlSeconds>604800 || ipPerMinuteLimit<1 || userPerMinuteLimit<1
                || redisKeyPrefix==null || redisKeyPrefix.isBlank() || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}"))
            throw new IllegalArgumentException("头像读取配置不合法");
        if(!publicBaseUrl.isEmpty()) {
            URI uri;
            try { uri=URI.create(publicBaseUrl); } catch(RuntimeException ex) {throw new IllegalArgumentException("头像公开根地址不合法");}
            if(!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getFragment()!=null
                    || uri.getPort()==0 || uri.getPort()>65535) throw new IllegalArgumentException("头像公开根地址不合法");
        }
    }
}
