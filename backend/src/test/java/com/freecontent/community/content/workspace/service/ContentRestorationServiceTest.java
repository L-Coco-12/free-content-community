package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.workspace.dto.VersionRequest;
import com.freecontent.community.content.workspace.service.impl.ContentRestorationServiceImpl;
import com.freecontent.community.content.workspace.ratelimit.ContentRestorationRateLimiter;
import com.freecontent.community.content.workspace.service.transaction.ContentRestorationTransactionService;
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

class ContentRestorationServiceTest {
    private final ContentRestorationTransactionService transactions = mock(ContentRestorationTransactionService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ContentRestorationRateLimiter limiter = mock(ContentRestorationRateLimiter.class);
    private final ContentRestorationService service = new ContentRestorationServiceImpl(transactions, json, limiter);
    private final VersionRequest input = json.convertValue(Map.of("expectedVersion", 7), VersionRequest.class);
    private final SessionPrincipal principal = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);

    @ParameterizedTest
    @MethodSource("badInputs")
    void invalidPathOrKeyIs422BeforeClaimingAnything(String content, String key, String location, String field) {
        var error = assertThrows(BusinessException.class, () -> service.restore(content, input, key, principal, "source", "req_test"));
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
        var error = assertThrows(BusinessException.class, () -> service.restore("content_1", input, "k".repeat(16), null, "source", "req_test"));
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, error.errorCode());
        verifyNoInteractions(transactions, limiter);
    }
}
