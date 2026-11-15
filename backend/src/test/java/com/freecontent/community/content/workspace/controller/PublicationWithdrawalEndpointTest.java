package com.freecontent.community.content.workspace.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.workspace.service.PublicationWithdrawalService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PublicationWithdrawalController.class)
@Import(SecurityConfiguration.class)
class PublicationWithdrawalEndpointTest {
    private static final String PATH = "/api/v1/creator/contents/content_1/publication";
    private static final String TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private PublicationWithdrawalService service;
    @MockitoBean private SessionAuthenticationService authentication;

    private SessionPrincipal authenticate() {
        var principal = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
        when(authentication.authenticate(TOKEN)).thenReturn(principal);
        return principal;
    }

    @Test
    void successIs204WithNoBodyAndDoesNotRequireAnIdempotencyKey() throws Exception {
        var actor = authenticate();
        mvc.perform(delete(PATH + "?expectedVersion=7").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(service).withdraw(eq("content_1"), eq("7"), eq(actor), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?expectedVersion=7&expectedVersion=8", "?expectedVersion=7&unknown=true", "?expectedVersion=7&version=7"})
    void missingDuplicatedOrUnknownQueryParametersAre400(String query) throws Exception {
        authenticate();
        mvc.perform(delete(PATH + query).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void aBodyCannotSupplyOrOverrideTheVersion() throws Exception {
        authenticate();
        mvc.perform(delete(PATH).header("Authorization", "Bearer " + TOKEN).contentType("application/json").content("{\"expectedVersion\":7}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(PATH + "?expectedVersion=7").header("Authorization", "Bearer " + TOKEN)
                .contentType("application/json").content("{\"expectedVersion\":8}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void guestsAre401BeforeTheUseCase() throws Exception {
        mvc.perform(delete(PATH + "?expectedVersion=7")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNT_RESTRICTED", "EMAIL_VERIFICATION_REQUIRED", "DEPENDENCY_UNAVAILABLE"})
    void authenticationErrorsKeepThePrivateWriteContract(String code) throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        var response = mvc.perform(delete(PATH + "?expectedVersion=7").header("Authorization", "Bearer " + TOKEN));
        if (code.equals("DEPENDENCY_UNAVAILABLE")) response.andExpect(status().isServiceUnavailable());
        else response.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(code.equals("EMAIL_VERIFICATION_REQUIRED") ? "PERMISSION_DENIED" : code));
        verifyNoInteractions(service);
    }
}
