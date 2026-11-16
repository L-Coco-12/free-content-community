package com.freecontent.community.content.interaction.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.interaction.ratelimit.ContentBookmarkRateLimiter;
import com.freecontent.community.content.interaction.service.impl.ContentBookmarkServiceImpl;
import com.freecontent.community.content.interaction.service.transaction.ContentBookmarkTransactionService;
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

class ContentBookmarkServiceTest {
    private final ContentBookmarkTransactionService transactions=mock(ContentBookmarkTransactionService.class);
    private final ContentBookmarkRateLimiter limiter=mock(ContentBookmarkRateLimiter.class);
    private final ContentBookmarkService service=new ContentBookmarkServiceImpl(transactions,limiter);
    private final SessionPrincipal actor=new SessionPrincipal("session",CurrentUser.from(LoginTestSupport.account(),List.of(UserRole.USER)),"digest",1);
    @Test void invalidRequestsDoNotAcquireQuota(){
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->service.bookmark("content",null,"source")).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,assertThrows(BusinessException.class,()->service.bookmark("bad!",actor,"source")).errorCode());verifyNoInteractions(limiter,transactions);
    }
    @Test void quotaFailureStopsTheTransaction(){
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire(actor.user().id(),"source");
        assertEquals(ErrorCode.RATE_LIMITED,assertThrows(BusinessException.class,()->service.bookmark("content",actor,"source")).errorCode());verifyNoInteractions(transactions);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void persistenceFailuresDoNotLeakInternalDetails(boolean transaction){
        RuntimeException failure=transaction?new CannotCreateTransactionException("private SQL"):new DataAccessResourceFailureException("private SQL");
        doThrow(failure).when(transactions).bookmark("content",actor);
        var error=assertThrows(BusinessException.class,()->service.bookmark("content",actor,"source"));assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private SQL"));
    }
    @Test void invalidUnbookmarkRequestsDoNotAcquireQuota(){
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->service.unbookmark("content",null,"source")).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,assertThrows(BusinessException.class,()->service.unbookmark("bad!",actor,"source")).errorCode());verifyNoInteractions(limiter,transactions);
    }
    @Test void unbookmarkQuotaFailureStopsTheTransaction(){
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire(actor.user().id(),"source");
        assertEquals(ErrorCode.RATE_LIMITED,assertThrows(BusinessException.class,()->service.unbookmark("content",actor,"source")).errorCode());verifyNoInteractions(transactions);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unbookmarkPersistenceFailuresAre503(boolean transaction){
        RuntimeException failure=transaction?new CannotCreateTransactionException("private SQL"):new DataAccessResourceFailureException("private SQL");
        doThrow(failure).when(transactions).unbookmark("content",actor);
        var error=assertThrows(BusinessException.class,()->service.unbookmark("content",actor,"source"));assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private SQL"));
    }
}
