package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.user.dto.SessionListView;

public interface MySessionsService {
    SessionListView list(SessionPrincipal actor,String sourceIp);
}
