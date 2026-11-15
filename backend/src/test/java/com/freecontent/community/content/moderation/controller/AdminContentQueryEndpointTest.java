package com.freecontent.community.content.moderation.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.dto.WorkspacePage;
import com.freecontent.community.content.moderation.service.AdminContentQueryService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.math.BigInteger;
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
class AdminContentQueryEndpointTest {
    private static final String PATH = "/api/v1/admin/contents", TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private AdminContentQueryService service;
    @MockitoBean private SessionAuthenticationService authentication;
    private SessionPrincipal authenticate(UserRole role) {
        var actor = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(role)), "digest", 1);
        when(authentication.authenticate(TOKEN)).thenReturn(actor); return actor;
    }
    @ParameterizedTest @EnumSource(value = UserRole.class, names = {"MODERATOR", "ADMIN"})
    void operatorsCanReadTheCollectionWithoutAnIdempotencyKey(UserRole role) throws Exception {
        var actor = authenticate(role);
        when(service.list(isNull(), isNull(), isNull(), isNull(), isNull(), eq(actor), anyString())).thenReturn(new WorkspacePage(List.of(), BigInteger.ONE, 20, 0));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.pageSize").value(20)).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).list(isNull(), isNull(), isNull(), isNull(), isNull(), eq(actor), anyString());
    }
    @Test
    void onlyTheFiveContractQueryFieldsAreBound() throws Exception {
        var actor = authenticate(UserRole.MODERATOR);
        mvc.perform(get(PATH + "?page=2&pageSize=10&authorId=user_1&lifecycle=DELETED&publicationStatus=TAKEN_DOWN")
                .header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
        verify(service).list(eq("2"), eq("10"), eq("user_1"), eq("DELETED"), eq("TAKEN_DOWN"), eq(actor), anyString());
    }
    @ParameterizedTest
    @ValueSource(strings = {"?kind=VIDEO", "?sort=updatedAt", "?state=PENDING", "?expectedVersion=7", "?page=1&page=2", "?authorId=a&authorId=b"})
    void unknownOrDuplicatedQueriesAre400BeforeTheUseCase(String query) throws Exception {
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
    void authenticationFailuresKeepThePrivateReadContract(String code) throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        var result = mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN));
        if (code.equals("DEPENDENCY_UNAVAILABLE")) result.andExpect(status().isServiceUnavailable());
        else result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(code.equals("EMAIL_VERIFICATION_REQUIRED") ? "PERMISSION_DENIED" : code));
        verifyNoInteractions(service);
    }
}
