package com.freecontent.community.content.workspace.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.workspace.dto.VersionRequest;
import com.freecontent.community.content.workspace.service.ContentRestorationService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContentRestorationController.class)
@Import(SecurityConfiguration.class)
class ContentRestorationEndpointTest {
    private static final String PATH = "/api/v1/creator/contents/content_1/restorations", TOKEN = "a".repeat(43), KEY = "k".repeat(16);
    private static final String BODY = "{\"expectedVersion\":7}";
    @Autowired private MockMvc mvc;
    @MockitoBean private ContentRestorationService service;
    @MockitoBean private SessionAuthenticationService authentication;
    private SessionPrincipal authenticate() {
        var actor = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
        when(authentication.authenticate(TOKEN)).thenReturn(actor); return actor;
    }
    @Test
    void bindsTheBodyAndKeyAndReturns200WithoutALocation() throws Exception {
        var actor = authenticate();
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Location")).andExpect(header().string("Cache-Control", "no-store"));
        var input = ArgumentCaptor.forClass(VersionRequest.class);
        verify(service).restore(eq("content_1"), input.capture(), eq(KEY), eq(actor), anyString(), anyString()); assertEquals(7, input.getValue().getExpectedVersion());
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedVersion\":null}", "{\"expectedVersion\":0}", "{\"expectedVersion\":-1}",
            "{\"expectedVersion\":2147483648}", "{\"expectedVersion\":999999999999999999999999}"})
    void invalidFieldValuesReturn422(String body) throws Exception {
        authenticate();
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType("application/json").content(body))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); verifyNoInteractions(service);
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "null", "{\"expectedVersion\":\"7\"}", "{\"expectedVersion\":7.0}", "{\"expectedVersion\":true}",
            "{\"expectedVersion\":7,\"sourceRevisionId\":\"r\"}"})
    void malformedOrUnknownBodyFieldsReturn400(String body) throws Exception {
        authenticate();
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST")); verifyNoInteractions(service);
    }
    @Test
    void missingRepeatedKeyAndQueryParametersReturn400() throws Exception {
        authenticate();
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY, KEY).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH + "?expectedVersion=7").header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test
    void guestsAre401AndWrongContentTypesAre415() throws Exception {
        mvc.perform(post(PATH).header("Idempotency-Key", KEY).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized()); authenticate();
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType("text/plain").content(BODY)).andExpect(status().isUnsupportedMediaType()); verifyNoInteractions(service);
    }
    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNT_RESTRICTED", "EMAIL_VERIFICATION_REQUIRED", "DEPENDENCY_UNAVAILABLE"})
    void authenticationFailuresKeepThePrivateContract(String code) throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        var response = mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType("application/json").content(BODY));
        if (code.equals("DEPENDENCY_UNAVAILABLE")) response.andExpect(status().isServiceUnavailable());
        else response.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(code.equals("EMAIL_VERIFICATION_REQUIRED") ? "PERMISSION_DENIED" : code)); verifyNoInteractions(service);
    }
}
