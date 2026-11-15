package com.freecontent.community.content.moderation.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.moderation.service.AdminContentQueryService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminContentQueryController.class)
@Import(SecurityConfiguration.class)
class AdminContentDetailEndpointTest {
    private static final String PATH = "/api/v1/admin/contents/content_1", TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private AdminContentQueryService service;
    @MockitoBean private SessionAuthenticationService authentication;
    private SessionPrincipal authenticate(UserRole role) {
        var actor = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(role)), "digest", 1);
        when(authentication.authenticate(TOKEN)).thenReturn(actor); return actor;
    }
    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"MODERATOR", "ADMIN"})
    void operatorsCanReadTheDetailWithoutAVersionOrIdempotencyKey(UserRole role) throws Exception {
        var actor = authenticate(role);
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).get(eq("content_1"), eq(actor), anyString());
    }
    @ParameterizedTest @ValueSource(strings = {"?expectedVersion=7", "?lifecycle=ACTIVE", "?revisionId=revision_1", "?authorId=user_1"})
    void everyQueryParameterIs400BeforeTheUseCase(String query) throws Exception {
        authenticate(UserRole.MODERATOR);
        mvc.perform(get(PATH + query).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }
    @Test
    void guestsAre401AndOrdinaryUsersAre403() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        authenticate(UserRole.USER); mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isForbidden()); verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings = {"ACCOUNT_RESTRICTED", "EMAIL_VERIFICATION_REQUIRED", "DEPENDENCY_UNAVAILABLE"})
    void authenticationFailuresKeepThePrivateDetailContract(String code) throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        var response = mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN));
        if (code.equals("DEPENDENCY_UNAVAILABLE")) response.andExpect(status().isServiceUnavailable());
        else response.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(code.equals("EMAIL_VERIFICATION_REQUIRED") ? "PERMISSION_DENIED" : code)); verifyNoInteractions(service);
    }
}
