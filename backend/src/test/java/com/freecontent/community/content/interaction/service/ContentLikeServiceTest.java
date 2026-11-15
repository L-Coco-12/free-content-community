package com.freecontent.community.content.interaction.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.interaction.ratelimit.ContentLikeRateLimiter;
import com.freecontent.community.content.interaction.service.impl.ContentLikeServiceImpl;
import com.freecontent.community.content.interaction.service.transaction.ContentLikeTransactionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentLikeServiceTest {
    private final ContentLikeTransactionService transactions=mock(ContentLikeTransactionService.class);
    private final ContentLikeRateLimiter limiter=mock(ContentLikeRateLimiter.class);
    private final ContentLikeService service=new ContentLikeServiceImpl(transactions,limiter);
    private final SessionPrincipal actor=new SessionPrincipal("session",CurrentUser.from(LoginTestSupport.account(),List.of(UserRole.USER)),"digest",1);
    @Test void missingIdentityAndInvalidIdsDoNotAcquireQuota() {
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->service.like("content",null,"source")).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,assertThrows(BusinessException.class,()->service.like("bad!",actor,"source")).errorCode());
        verifyNoInteractions(limiter,transactions);
    }
    @Test void rateLimitStopsTheTransaction() {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire(actor.user().id(),"source");
        assertEquals(ErrorCode.RATE_LIMITED,assertThrows(BusinessException.class,()->service.like("content",actor,"source")).errorCode());
        verifyNoInteractions(transactions);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void persistenceFailuresAre503WithoutInternalDetails(boolean transaction) {
        RuntimeException failure=transaction?new CannotCreateTransactionException("private SQL"):new DataAccessResourceFailureException("private SQL");
        doThrow(failure).when(transactions).like("content",actor);
        var error=assertThrows(BusinessException.class,()->service.like("content",actor,"source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private SQL"));
    }
    @Test void invalidCancellationStopsBeforeQuotaAndTransaction() {
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->service.unlike("content",null,"source")).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,assertThrows(BusinessException.class,()->service.unlike("bad!",actor,"source")).errorCode());
        verifyNoInteractions(limiter,transactions);
    }
    @Test void cancellationUsesTheSameLimiterAndDoesNotStartATransactionWhenDenied() {
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire(actor.user().id(),"source");
        assertEquals(ErrorCode.RATE_LIMITED,assertThrows(BusinessException.class,()->service.unlike("content",actor,"source")).errorCode());verifyNoInteractions(transactions);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancellationPersistenceFailuresBecome503(boolean transaction) {
        RuntimeException failure=transaction?new CannotCreateTransactionException("private SQL"):new DataAccessResourceFailureException("private SQL");
        doThrow(failure).when(transactions).unlike("content",actor);
        var error=assertThrows(BusinessException.class,()->service.unlike("content",actor,"source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private SQL"));
    }
}
