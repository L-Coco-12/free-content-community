package com.freecontent.community.content.workspace.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.workspace.dto.PublicationRequest;
import com.freecontent.community.content.workspace.service.ContentRepublicationService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContentRepublicationController.class)
@Import(SecurityConfiguration.class)
class ContentRepublicationEndpointTest {
    private static final String PATH = "/api/v1/creator/contents/content_1/publication";
    private static final String TOKEN = "a".repeat(43);
    private static final String BODY = "{\"expectedVersion\":7,\"revisionId\":\"revision_1\"}";
    @Autowired private MockMvc mvc;
    @MockitoBean private ContentRepublicationService service;
    @MockitoBean private SessionAuthenticationService authentication;

    private SessionPrincipal authenticate() {
        var actor = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
        when(authentication.authenticate(TOKEN)).thenReturn(actor);
        return actor;
    }

    @Test
    void successBindsBodyWithoutAnIdempotencyKeyAndReturns200() throws Exception {
        var actor = authenticate();
        mvc.perform(put(PATH).header("Authorization", "Bearer " + TOKEN).contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        var input = ArgumentCaptor.forClass(PublicationRequest.class);
        verify(service).republish(eq("content_1"), input.capture(), eq(actor), anyString(), anyString());
        assertEquals(7, input.getValue().getExpectedVersion()); assertEquals("revision_1", input.getValue().getRevisionId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedVersion\":7}", "{\"revisionId\":\"r\"}",
            "{\"expectedVersion\":null,\"revisionId\":\"r\"}", "{\"expectedVersion\":0,\"revisionId\":\"r\"}",
            "{\"expectedVersion\":-1,\"revisionId\":\"r\"}", "{\"expectedVersion\":2147483648,\"revisionId\":\"r\"}",
            "{\"expectedVersion\":999999999999999999999999,\"revisionId\":\"r\"}",
            "{\"expectedVersion\":7,\"revisionId\":null}", "{\"expectedVersion\":7,\"revisionId\":\"\"}",
            "{\"expectedVersion\":7,\"revisionId\":\"bad.id\"}"})
    void missingNullOrOutOfRangeFieldsReturn422(String body) throws Exception {
        authenticate();
        mvc.perform(put(PATH).header("Authorization", "Bearer " + TOKEN).contentType("application/json").content(body))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "null", "{\"expectedVersion\":\"7\",\"revisionId\":\"r\"}",
            "{\"expectedVersion\":true,\"revisionId\":\"r\"}", "{\"expectedVersion\":7.0,\"revisionId\":\"r\"}",
            "{\"expectedVersion\":7,\"revisionId\":12}", "{\"expectedVersion\":7,\"revisionId\":false}",
            "{\"expectedVersion\":7,\"revisionId\":\"r\",\"title\":\"不能编辑\"}"})
    void malformedBodyTypesAndUnknownFieldsReturn400(String body) throws Exception {
        authenticate();
        mvc.perform(put(PATH).header("Authorization", "Bearer " + TOKEN).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void bodyCannotBeSuppliedOrOverriddenByQueryParameters() throws Exception {
        authenticate();
        mvc.perform(put(PATH + "?expectedVersion=7&revisionId=r").header("Authorization", "Bearer " + TOKEN).contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void guestsAre401AndWrongContentTypeIs415() throws Exception {
        mvc.perform(put(PATH).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        authenticate();
        mvc.perform(put(PATH).header("Authorization", "Bearer " + TOKEN).contentType("text/plain").content(BODY))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNT_RESTRICTED", "EMAIL_VERIFICATION_REQUIRED", "DEPENDENCY_UNAVAILABLE"})
    void authenticationFailuresKeepTheDeclaredContract(String code) throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        var result = mvc.perform(put(PATH).header("Authorization", "Bearer " + TOKEN).contentType("application/json").content(BODY));
        if (code.equals("DEPENDENCY_UNAVAILABLE")) result.andExpect(status().isServiceUnavailable());
        else result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(code.equals("EMAIL_VERIFICATION_REQUIRED") ? "PERMISSION_DENIED" : code));
        verifyNoInteractions(service);
    }
}
