package com.freecontent.community.content.workspace.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.dto.RevisionView;
import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.content.workspace.dto.CreateDraftRequest;
import com.freecontent.community.content.workspace.service.ContentDraftCreationService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContentDraftCreationController.class)
@Import(SecurityConfiguration.class)
class ContentDraftCreationEndpointTest {
    private static final String PATH = "/api/v1/creator/contents/content_1/drafts";
    private static final String TOKEN = "a".repeat(43), KEY = "k".repeat(16);
    private static final String BODY = "{\"expectedVersion\":7}";
    @Autowired private MockMvc mvc;
    @MockitoBean private ContentDraftCreationService service;
    @MockitoBean private SessionAuthenticationService authentication;

    private SessionPrincipal authenticate() {
        var actor = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
        when(authentication.authenticate(TOKEN)).thenReturn(actor);
        return actor;
    }

    @Test
    void successReturns201TheWorkspaceAndTheNewRevisionLocation() throws Exception {
        var actor = authenticate();
        when(service.create(eq("content_1"), any(), eq(KEY), eq(actor), anyString())).thenReturn(workspace());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/creator/contents/content_1/revisions/revision_new"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.version").value(8)).andExpect(jsonPath("$.data.workingRevision.status").value("DRAFT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedVersion\":null}", "{\"expectedVersion\":0}", "{\"expectedVersion\":-1}",
            "{\"expectedVersion\":2147483648}", "{\"expectedVersion\":999999999999999999999999}",
            "{\"expectedVersion\":7,\"sourceRevisionId\":null}", "{\"expectedVersion\":7,\"sourceRevisionId\":\"\"}",
            "{\"expectedVersion\":7,\"sourceRevisionId\":\"bad.id\"}"})
    void invalidFieldValuesAre422BeforeTheUseCase(String body) throws Exception {
        authenticate();
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"expectedVersion\":\"7\"}", "{\"expectedVersion\":true}", "{\"expectedVersion\":7.5}",
            "{\"expectedVersion\":7,\"sourceRevisionId\":3}", "{\"expectedVersion\":7,\"title\":\"不能直接编辑\"}"})
    void malformedTypesOrUnknownFieldsAre400(String body) throws Exception {
        authenticate();
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void missingOrRepeatedKeyAndUnknownQueryParametersAre400() throws Exception {
        authenticate();
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH + "?sourceRevisionId=another").header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void guestsCannotCreateDraftsAndUnsupportedContentTypesAre415() throws Exception {
        mvc.perform(post(PATH).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        authenticate();
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY)
                .contentType(MediaType.TEXT_PLAIN).content(BODY)).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }

    @Test
    void unverifiedCredentialsMapToTheDeclaredPermissionError() throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED));
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        verifyNoInteractions(service);
    }

    private ContentWorkspace workspace() {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        var revision = new RevisionView("revision_new", "content_1", 2, "DRAFT", "历史", "正文", null, List.of(), null, List.of(), now, now);
        return new ContentWorkspace("content_1", "user1", ContentKind.ARTICLE, "ACTIVE", "UNPUBLISHED", 8, revision, null, null, now, now, null);
    }
}
