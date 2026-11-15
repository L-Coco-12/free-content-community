package com.freecontent.community.content.moderation.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.moderation.dto.*;
import com.freecontent.community.content.moderation.service.ContentTakeDownService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContentTakeDownController.class)
@Import(SecurityConfiguration.class)
class ContentTakeDownEndpointTest {
    private static final String PATH = "/api/v1/admin/contents/content_1/take-downs", TOKEN = "a".repeat(43), KEY = "k".repeat(16);
    private static final String BODY = "{\"expectedVersion\":7,\"reasonCode\":\"SPAM\",\"reason\":\"需要整改\"}";
    @Autowired private MockMvc mvc;
    @MockitoBean private ContentTakeDownService service;
    @MockitoBean private SessionAuthenticationService authentication;
    private SessionPrincipal authenticate(UserRole role) {
        var actor = new SessionPrincipal("session",CurrentUser.from(LoginTestSupport.account(),List.of(role)),"digest",1);
        when(authentication.authenticate(TOKEN)).thenReturn(actor); return actor;
    }
    @ParameterizedTest @EnumSource(value = UserRole.class,names = {"MODERATOR","ADMIN"})
    void operatorsPostTheVersionReasonAndKeyAndReceive201(UserRole role) throws Exception {
        var actor = authenticate(role);
        when(service.takeDown(eq("content_1"),any(),eq(KEY),eq(actor),anyString(),anyString())).thenReturn(new ModerationAction("action_1","CONTENT","content_1","TAKE_DOWN","需要整改",actor.user().id(),Instant.parse("2026-11-13T00:00:00Z"),8));
        mvc.perform(post(PATH).header("Authorization","Bearer " + TOKEN).header("Idempotency-Key",KEY).contentType("application/json").content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value("action_1")).andExpect(jsonPath("$.data.resultingVersion").value(8)).andExpect(header().string("Cache-Control","no-store"));
        var input = ArgumentCaptor.forClass(TakeDownRequest.class);
        verify(service).takeDown(eq("content_1"),input.capture(),eq(KEY),eq(actor),anyString(),anyString());
        assertEquals(7,input.getValue().getExpectedVersion()); assertEquals(TakeDownRequest.ReasonCode.SPAM,input.getValue().getReasonCode()); assertEquals("需要整改",input.getValue().getReason());
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedVersion\":7}", "{\"expectedVersion\":null,\"reasonCode\":\"SPAM\",\"reason\":\"原因\"}",
            "{\"expectedVersion\":0,\"reasonCode\":\"SPAM\",\"reason\":\"原因\"}", "{\"expectedVersion\":-1,\"reasonCode\":\"SPAM\",\"reason\":\"原因\"}",
            "{\"expectedVersion\":2147483648,\"reasonCode\":\"SPAM\",\"reason\":\"原因\"}", "{\"expectedVersion\":999999999999999999999999,\"reasonCode\":\"SPAM\",\"reason\":\"原因\"}",
            "{\"expectedVersion\":7,\"reasonCode\":null,\"reason\":\"原因\"}", "{\"expectedVersion\":7,\"reasonCode\":\"SPAM\",\"reason\":null}",
            "{\"expectedVersion\":7,\"reasonCode\":\"SPAM\",\"reason\":\"\"}", "{\"expectedVersion\":7,\"reasonCode\":\"SPAM\",\"reason\":\"   \"}"})
    void invalidValuesReturn422BeforeTheUseCase(String body) throws Exception {
        authenticate(UserRole.MODERATOR);
        mvc.perform(post(PATH).header("Authorization","Bearer " + TOKEN).header("Idempotency-Key",KEY).contentType("application/json").content(body))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); verifyNoInteractions(service);
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "null", "{\"expectedVersion\":\"7\",\"reasonCode\":\"SPAM\",\"reason\":\"原因\"}",
            "{\"expectedVersion\":7.0,\"reasonCode\":\"SPAM\",\"reason\":\"原因\"}", "{\"expectedVersion\":true,\"reasonCode\":\"SPAM\",\"reason\":\"原因\"}",
            "{\"expectedVersion\":7,\"reasonCode\":\"NOPE\",\"reason\":\"原因\"}", "{\"expectedVersion\":7,\"reasonCode\":0,\"reason\":\"原因\"}",
            "{\"expectedVersion\":7,\"reasonCode\":\"SPAM\",\"reason\":12}", "{\"expectedVersion\":7,\"reasonCode\":\"SPAM\",\"reason\":\"原因\",\"operatorId\":\"other\"}"})
    void malformedTypesEnumsAndUnknownFieldsReturn400(String body) throws Exception {
        authenticate(UserRole.MODERATOR);
        mvc.perform(post(PATH).header("Authorization","Bearer " + TOKEN).header("Idempotency-Key",KEY).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST")); verifyNoInteractions(service);
    }
    @Test
    void reasonLengthUsesUnicodeCodePointsAndDoesNotTrim() throws Exception {
        authenticate(UserRole.MODERATOR); var json = JsonMapper.builder().build(); String reason = "😀".repeat(1000);
        mvc.perform(post(PATH).header("Authorization","Bearer " + TOKEN).header("Idempotency-Key",KEY).contentType("application/json")
                .content(json.writeValueAsString(Map.of("expectedVersion",7,"reasonCode","OTHER","reason",reason)))).andExpect(status().isCreated());
        var input = ArgumentCaptor.forClass(TakeDownRequest.class); verify(service).takeDown(anyString(),input.capture(),anyString(),any(),anyString(),anyString()); assertEquals(reason,input.getValue().getReason());
        clearInvocations(service);
        mvc.perform(post(PATH).header("Authorization","Bearer " + TOKEN).header("Idempotency-Key",KEY).contentType("application/json")
                .content(json.writeValueAsString(Map.of("expectedVersion",7,"reasonCode","OTHER","reason",reason + "😀")))).andExpect(status().isUnprocessableContent()); verifyNoInteractions(service);
    }
    @Test
    void missingOrRepeatedKeyAndAnyQueryReturn400() throws Exception {
        authenticate(UserRole.MODERATOR);
        mvc.perform(post(PATH).header("Authorization","Bearer " + TOKEN).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("Authorization","Bearer " + TOKEN).header("Idempotency-Key",KEY,KEY).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH + "?expectedVersion=7").header("Authorization","Bearer " + TOKEN).header("Idempotency-Key",KEY).contentType("application/json").content(BODY)).andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    @Test
    void guestsAndOrdinaryUsersAreDeniedAndWrongContentTypeIs415() throws Exception {
        mvc.perform(post(PATH).header("Idempotency-Key",KEY).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        authenticate(UserRole.USER); mvc.perform(post(PATH).header("Authorization","Bearer " + TOKEN).header("Idempotency-Key",KEY).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        authenticate(UserRole.MODERATOR); mvc.perform(post(PATH).header("Authorization","Bearer " + TOKEN).header("Idempotency-Key",KEY).contentType("text/plain").content(BODY)).andExpect(status().isUnsupportedMediaType()); verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings = {"ACCOUNT_RESTRICTED","EMAIL_VERIFICATION_REQUIRED","DEPENDENCY_UNAVAILABLE"})
    void authenticationErrorsKeepThePrivateWriteContract(String code) throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        var response = mvc.perform(post(PATH).header("Authorization","Bearer " + TOKEN).header("Idempotency-Key",KEY).contentType("application/json").content(BODY));
        if (code.equals("DEPENDENCY_UNAVAILABLE")) response.andExpect(status().isServiceUnavailable());
        else response.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(code.equals("EMAIL_VERIFICATION_REQUIRED") ? "PERMISSION_DENIED" : code)); verifyNoInteractions(service);
    }
}
