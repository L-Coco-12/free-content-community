package com.freecontent.community.content.interaction.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.browse.dto.ContentPageView;

public interface MyBookmarksService {
    ContentPageView list(String cursor,String limit,SessionPrincipal actor,String sourceIp);
}
