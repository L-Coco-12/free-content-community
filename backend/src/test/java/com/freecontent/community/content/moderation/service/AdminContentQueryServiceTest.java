package com.freecontent.community.content.moderation.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.moderation.service.impl.AdminContentQueryServiceImpl;
import com.freecontent.community.content.moderation.service.transaction.AdminContentQueryTransactionService;
import com.freecontent.community.content.moderation.ratelimit.AdminContentQueryRateLimiter;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminContentQueryServiceTest {
    private final AdminContentQueryTransactionService transactions = mock(AdminContentQueryTransactionService.class);
    private final AdminContentQueryRateLimiter limiter = mock(AdminContentQueryRateLimiter.class);
    private final AdminContentQueryService service = new AdminContentQueryServiceImpl(transactions, limiter);
    private final SessionPrincipal actor = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.MODERATOR)), "digest", 1);
    @ParameterizedTest
    @CsvSource({"page,abc,MALFORMED_REQUEST", "page,1.0,MALFORMED_REQUEST", "page,0,VALIDATION_ERROR", "page,-1,VALIDATION_ERROR",
            "pageSize,abc,MALFORMED_REQUEST", "pageSize,0,VALIDATION_ERROR", "pageSize,101,VALIDATION_ERROR", "pageSize,999999999999999999999999,VALIDATION_ERROR",
            "authorId,bad.id,VALIDATION_ERROR", "authorId,中文,VALIDATION_ERROR", "lifecycle,ALL,MALFORMED_REQUEST", "lifecycle,active,MALFORMED_REQUEST",
            "publicationStatus,DRAFT,MALFORMED_REQUEST", "publicationStatus,published,MALFORMED_REQUEST"})
    void invalidQueriesAreRejectedBeforeTheTransaction(String field, String raw, String code) {
        String[] input = new String[5]; int position = List.of("page", "pageSize", "authorId", "lifecycle", "publicationStatus").indexOf(field); input[position] = raw;
        var error = assertThrows(BusinessException.class, () -> service.list(input[0], input[1], input[2], input[3], input[4], actor, "source"));
        assertEquals(ErrorCode.valueOf(code), error.errorCode()); assertEquals("query", error.violations().get(0).location()); assertEquals(field, error.violations().get(0).path());
        verifyNoInteractions(transactions, limiter);
    }
    @Test
    void emptyAndOversizedAuthorIdsAre422() {
        for (String id : List.of("", "a".repeat(65))) {
            var error = assertThrows(BusinessException.class, () -> service.list(null, null, id, null, null, actor, "source"));
            assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        }
        verifyNoInteractions(transactions, limiter);
    }
    @Test
    void missingPrincipalIs401() {
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, assertThrows(BusinessException.class,
                () -> service.list(null, null, null, null, null, null, "source")).errorCode()); verifyNoInteractions(transactions, limiter);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void databaseAndTransactionFailuresBecome503(boolean transaction) {
        RuntimeException failure = transaction ? new CannotCreateTransactionException("sensitive connection detail") : new DataAccessResourceFailureException("sensitive SQL detail");
        when(transactions.list(any(), anyInt(), isNull(), isNull(), isNull(), eq(actor))).thenThrow(failure);
        var error = assertThrows(BusinessException.class, () -> service.list(null, null, null, null, null, actor, "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode()); assertFalse(error.getMessage().contains("sensitive"));
    }
    @ParameterizedTest @NullSource
    @ValueSource(strings = {"", "bad.id", "中文", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidDetailIdIs422BeforeQuotaAndTransaction(String id) {
        var error = assertThrows(BusinessException.class, () -> service.get(id, actor, "source"));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode()); assertEquals("path", error.violations().get(0).location()); assertEquals("contentId", error.violations().get(0).path());
        verifyNoInteractions(transactions, limiter);
    }
    @Test
    void detailRequiresAnIdentityBeforeUsingQuota() {
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, assertThrows(BusinessException.class, () -> service.get("content_1", null, "source")).errorCode());
        verifyNoInteractions(transactions, limiter);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void detailDependencyFailuresBecome503WithoutSqlDetails(boolean transaction) {
        RuntimeException failure = transaction ? new CannotCreateTransactionException("sensitive connection detail") : new DataAccessResourceFailureException("sensitive SQL detail");
        when(transactions.read("content_1", actor)).thenThrow(failure);
        var error = assertThrows(BusinessException.class, () -> service.get("content_1", actor, "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode()); assertFalse(error.getMessage().contains("sensitive"));
    }
}
