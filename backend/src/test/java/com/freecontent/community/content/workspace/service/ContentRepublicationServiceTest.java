package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.workspace.dto.PublicationRequest;
import com.freecontent.community.content.workspace.ratelimit.ContentRepublicationRateLimiter;
import com.freecontent.community.content.workspace.service.impl.ContentRepublicationServiceImpl;
import com.freecontent.community.content.workspace.service.transaction.ContentRepublicationTransactionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentRepublicationServiceTest {
    private final ContentRepublicationTransactionService transactions = mock(ContentRepublicationTransactionService.class);
    private final ContentRepublicationRateLimiter limiter = mock(ContentRepublicationRateLimiter.class);
    private final ContentRepublicationService service = new ContentRepublicationServiceImpl(transactions, limiter);
    private final SessionPrincipal actor = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
    private final PublicationRequest input = input();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "bad.id", "内容", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidPathIs422BeforeTransaction(String content) {
        var error = assertThrows(BusinessException.class, () -> service.republish(content, input, actor, "source", "req_test"));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertEquals("path", error.violations().get(0).location()); assertEquals("contentId", error.violations().get(0).path());
        verifyNoInteractions(transactions, limiter);
    }

    @Test
    void missingIdentityIs401BeforeTransaction() {
        var error = assertThrows(BusinessException.class, () -> service.republish("content_1", input, null, "source", "req_test"));
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, error.errorCode()); verifyNoInteractions(transactions, limiter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseAndTransactionFailuresBecome503WithoutLeakingDetails(boolean transaction) {
        RuntimeException failure = transaction ? new CannotCreateTransactionException("sensitive connection detail") : new DataAccessResourceFailureException("sensitive SQL detail");
        when(transactions.republish("content_1", input, actor, "req_test")).thenThrow(failure);
        var error = assertThrows(BusinessException.class, () -> service.republish("content_1", input, actor, "source", "req_test"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode()); assertFalse(error.getMessage().contains("sensitive"));
    }

    private PublicationRequest input() {
        var value = new PublicationRequest(); value.setExpectedVersion(JsonMapper.builder().build().readTree("8")); value.setRevisionId("revision_1"); return value;
    }
}
