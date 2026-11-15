package com.freecontent.community.user.service.read;

import com.freecontent.community.user.config.AvatarAccessProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/** 只生成稳定业务地址，无存储 I/O；批量作者投影不会逐个签名或 HEAD。 */
@Component
public class PublicAvatarUrlFactory {
    private final AvatarAccessProperties properties;
    public PublicAvatarUrlFactory(AvatarAccessProperties properties) {this.properties=properties;}
    public String url(String userId,String assetId) {
        if(userId==null || !userId.matches("[A-Za-z0-9_-]{1,64}") || assetId==null || !assetId.matches("[A-Za-z0-9_-]{1,64}")) return null;
        UriComponentsBuilder base;
        if(!properties.publicBaseUrl().isEmpty()) base=UriComponentsBuilder.fromUriString(properties.publicBaseUrl());
        else if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)
            base=ServletUriComponentsBuilder.fromCurrentContextPath();
        else return null;
        return base.pathSegment("api","v1","users",userId,"avatar").queryParam("assetId",assetId).build().toUriString();
    }
}
