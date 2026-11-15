package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.dto.MediaAccess;

public interface PublicAvatarService {
    MediaAccess getAvatar(String userId,String assetId,SessionPrincipal viewer,String sourceIp);
}
