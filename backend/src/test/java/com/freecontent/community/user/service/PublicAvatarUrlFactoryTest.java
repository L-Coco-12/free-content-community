package com.freecontent.community.user.service;

import com.freecontent.community.user.config.AvatarAccessProperties;
import com.freecontent.community.user.service.read.PublicAvatarUrlFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicAvatarUrlFactoryTest {
    @AfterEach void clearRequest(){RequestContextHolder.resetRequestAttributes();}
    @Test void stableUrlContainsTheUserAndAssetWithoutAnyStorageSignature() {
        var factory=new PublicAvatarUrlFactory(new AvatarAccessProperties(300,120,60,"test:","https://community.example/app"));
        assertEquals("https://community.example/app/api/v1/users/user_1/avatar?assetId=avatar_1",factory.url("user_1","avatar_1"));
    }
    @Test void defaultOriginUsesTheActualHttpRequestAndContextPath() {
        var request=new MockHttpServletRequest();request.setScheme("https");request.setServerName("community.example");request.setServerPort(443);request.setContextPath("/app");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var factory=new PublicAvatarUrlFactory(new AvatarAccessProperties(300,120,60,"test:",""));
        assertEquals("https://community.example/app/api/v1/users/user_1/avatar?assetId=avatar_1",factory.url("user_1","avatar_1"));
    }
    @Test void unavailableAvatarOrHttpContextDegradesToNull() {
        var factory=new PublicAvatarUrlFactory(new AvatarAccessProperties(300,120,60,"test:",""));
        assertNull(factory.url("user_1",null));assertNull(factory.url("user_1","avatar_1"));
        assertNull(factory.url("../other","avatar_1"));
    }
    @Test void explicitPublicOriginIgnoresTheRequestHostAndNewBindingsChangeTheUrl() {
        var request=new MockHttpServletRequest();request.setServerName("untrusted.example");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var factory=new PublicAvatarUrlFactory(new AvatarAccessProperties(300,120,60,"test:","https://community.example/"));
        assertEquals("https://community.example/api/v1/users/user_1/avatar?assetId=avatar_1",factory.url("user_1","avatar_1"));
        assertNotEquals(factory.url("user_1","avatar_1"),factory.url("user_1","avatar_2"));
    }
}
