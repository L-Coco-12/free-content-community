package com.freecontent.community.content.moderation.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.moderation.dto.TakeDownRequest;
import com.freecontent.community.content.moderation.service.impl.ContentTakeDownServiceImpl;
import com.freecontent.community.content.moderation.ratelimit.ContentTakeDownRateLimiter;
import com.freecontent.community.content.moderation.service.transaction.ContentTakeDownTransactionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentTakeDownServiceTest {
    private final ContentTakeDownTransactionService transactions = mock(ContentTakeDownTransactionService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ContentTakeDownRateLimiter limiter = mock(ContentTakeDownRateLimiter.class);
    private final ContentTakeDownService service = new ContentTakeDownServiceImpl(transactions, json, limiter);
    private final TakeDownRequest input = json.convertValue(Map.of("expectedVersion", 7, "reasonCode", "SPAM", "reason", "需要整改"), TakeDownRequest.class);
    private final SessionPrincipal principal = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.MODERATOR)), "digest", 1);

    @ParameterizedTest
    @MethodSource("badInputs")
    void invalidPathOrKeyIs422BeforeClaimingAnything(String content, String key, String location, String field) {
        var error = assertThrows(BusinessException.class, () -> service.takeDown(content, input, key, principal, "source", "req_test"));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertEquals(location, error.violations().get(0).location());
        assertEquals(field, error.violations().get(0).path());
        verifyNoInteractions(transactions, limiter);
    }

    static Stream<Arguments> badInputs() {
        return Stream.concat(Stream.of(null, "", "bad.id", "内容", "a".repeat(65)).map(content ->
                        Arguments.of(content, "k".repeat(16), "path", "contentId")),
                Stream.of(null, "", "k".repeat(15), "k".repeat(129)).map(key -> Arguments.of("content_1", key, "header", "Idempotency-Key")));
    }

    @Test
    void missingPrincipalIs401BeforeClaimingAnything() {
        var error = assertThrows(BusinessException.class, () -> service.takeDown("content_1", input, "k".repeat(16), null, "source", "req_test"));
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, error.errorCode());
        verifyNoInteractions(transactions, limiter);
    }
    @Test
    void uniqueKeyRaceIsRetriedOnceAndCanRecoverTheSuccessfulResultWithoutQuota() {
        var action = new com.freecontent.community.content.moderation.dto.ModerationAction("action_1","CONTENT","content_1","TAKE_DOWN","需要整改",principal.user().id(),java.time.Instant.EPOCH,8);
        var replay = new ContentTakeDownTransactionService.Claim(null,"content_1",new ContentTakeDownTransactionService.Selection(7,"SPAM","需要整改"),action);
        when(transactions.claim(eq("content_1"),eq(input),anyString(),anyString(),eq(principal))).thenThrow(new org.springframework.dao.DuplicateKeyException("race")).thenReturn(replay);
        assertEquals(action,service.takeDown("content_1",input,"k".repeat(16),principal,"source","req_test"));
        verify(transactions,times(2)).claim(eq("content_1"),eq(input),anyString(),anyString(),eq(principal)); verifyNoInteractions(limiter);
    }
    @Test
    void repeatedUniqueKeyCollisionIsBoundedAndBecomes503() {
        when(transactions.claim(eq("content_1"),eq(input),anyString(),anyString(),eq(principal))).thenThrow(new org.springframework.dao.DuplicateKeyException("race"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(BusinessException.class,() -> service.takeDown("content_1",input,"k".repeat(16),principal,"source","req_test")).errorCode());
        verify(transactions,times(2)).claim(eq("content_1"),eq(input),anyString(),anyString(),eq(principal)); verifyNoInteractions(limiter);
    }
}
