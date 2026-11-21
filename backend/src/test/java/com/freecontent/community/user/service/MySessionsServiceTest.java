package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.ratelimit.SessionQueryRateLimiter;
import com.freecontent.community.user.service.impl.MySessionsServiceImpl;
import com.freecontent.community.user.service.transaction.MySessionsTransactionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MySessionsServiceTest {
    private final MySessionsTransactionService transactions=mock(MySessionsTransactionService.class);
    private final SessionQueryRateLimiter limiter=mock(SessionQueryRateLimiter.class);
    private final MySessionsService service=new MySessionsServiceImpl(transactions,limiter);
    private final SessionPrincipal actor=new SessionPrincipal("session1",CurrentUser.from(LoginTestSupport.account(),List.of(UserRole.USER)),TokenDigest.sha256("token"),1);
    @Test void missingAuthenticationDoesNotReachQuotaOrData(){assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->service.list(null,"source")).errorCode());verifyNoInteractions(limiter,transactions);}
    @Test void failedQuotaPreventsStartingTheReadTransaction(){
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire(actor.user().id(),"source");
        assertEquals(ErrorCode.RATE_LIMITED,assertThrows(BusinessException.class,()->service.list(actor,"source")).errorCode());verifyNoInteractions(transactions);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void databaseAndTransactionFailuresReturn503WithoutInternalDetails(boolean transaction){
        RuntimeException failure=transaction?new CannotCreateTransactionException("private connection detail"):new DataAccessResourceFailureException("private connection detail");
        doThrow(failure).when(transactions).list(actor);var error=assertThrows(BusinessException.class,()->service.list(actor,"source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private"));
    }
}
