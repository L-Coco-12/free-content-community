package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.workspace.service.impl.PublicationWithdrawalServiceImpl;
import com.freecontent.community.content.workspace.service.transaction.PublicationWithdrawalTransactionService;
import com.freecontent.community.content.workspace.ratelimit.PublicationWithdrawalRateLimiter;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicationWithdrawalServiceTest {
    private final PublicationWithdrawalTransactionService transactions = mock(PublicationWithdrawalTransactionService.class);
    private final PublicationWithdrawalRateLimiter limiter = mock(PublicationWithdrawalRateLimiter.class);
    private final PublicationWithdrawalService service = new PublicationWithdrawalServiceImpl(transactions, limiter);
    private final SessionPrincipal actor = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void invalidArgumentsAreRejectedBeforeTheTransaction(String content, String version, ErrorCode code, String location, String field) {
        var error = assertThrows(BusinessException.class, () -> service.withdraw(content, version, actor, "source", "req_test"));
        assertEquals(code, error.errorCode());
        assertEquals(location, error.violations().get(0).location());
        assertEquals(field, error.violations().get(0).path());
        verifyNoInteractions(transactions, limiter);
    }

    static Stream<Arguments> invalidArguments() {
        return Stream.concat(Stream.of(null, "", "bad.id", "内容", "a".repeat(65)).map(content ->
                        Arguments.of(content, "7", ErrorCode.VALIDATION_ERROR, "path", "contentId")),
                Stream.concat(Stream.of(null, "", "no", "1.0").map(version ->
                                Arguments.of("content_1", version, ErrorCode.MALFORMED_REQUEST, "query", "expectedVersion")),
                        Stream.of("0", "-1", "2147483648", "999999999999999999999999").map(version ->
                                Arguments.of("content_1", version, ErrorCode.VALIDATION_ERROR, "query", "expectedVersion"))));
    }

    @Test
    void missingIdentityIs401BeforeTheTransaction() {
        var error = assertThrows(BusinessException.class, () -> service.withdraw("content_1", "7", null, "source", "req_test"));
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, error.errorCode());
        verifyNoInteractions(transactions, limiter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dependencyFailuresBecome503WithoutSqlDetails(boolean transactionFailure) {
        RuntimeException failure = transactionFailure ? new CannotCreateTransactionException("sensitive connection detail")
                : new DataAccessResourceFailureException("sensitive SQL detail");
        doThrow(failure).when(transactions).withdraw("content_1", 7, actor, "req_test");
        var error = assertThrows(BusinessException.class, () -> service.withdraw("content_1", "7", actor, "source", "req_test"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode());
        assertFalse(error.getMessage().contains("sensitive"));
    }
}
