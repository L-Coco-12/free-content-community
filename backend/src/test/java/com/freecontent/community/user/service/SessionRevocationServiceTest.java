package com.freecontent.community.user.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.ratelimit.SessionRevocationRateLimiter;
import com.freecontent.community.user.service.impl.SessionRevocationServiceImpl;
import com.freecontent.community.user.service.transaction.SessionRevocationTransactionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRevocationServiceTest {
    private final SessionRevocationTransactionService transactions=mock(SessionRevocationTransactionService.class);
    private final SessionRevocationRateLimiter limiter=mock(SessionRevocationRateLimiter.class);
    private final SessionRevocationService service=new SessionRevocationServiceImpl(transactions,limiter);
    private final SessionPrincipal actor=new SessionPrincipal("current",CurrentUser.from(LoginTestSupport.account(),List.of(UserRole.USER)),TokenDigest.sha256("token"),1);
    @Test void invalidInputDoesNotReachQuotaOrData(){
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->service.revoke("target",null,"source")).errorCode());
        for(String id:new String[]{null,"","bad!","a".repeat(65)})assertEquals(ErrorCode.VALIDATION_ERROR,assertThrows(BusinessException.class,()->service.revoke(id,actor,"source")).errorCode());
        verifyNoInteractions(limiter,transactions);
    }
    @Test void exhaustedQuotaPreventsDeletion(){
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire(actor.user().id(),"source");
        assertEquals(ErrorCode.RATE_LIMITED,assertThrows(BusinessException.class,()->service.revoke("target",actor,"source")).errorCode());verifyNoInteractions(transactions);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void databaseAndTransactionFailuresAre503WithoutInternalDetails(boolean transaction){
        RuntimeException failure=transaction?new CannotCreateTransactionException("private connection detail"):new DataAccessResourceFailureException("private connection detail");
        doThrow(failure).when(transactions).revoke("target",actor);var error=assertThrows(BusinessException.class,()->service.revoke("target",actor,"source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private"));
    }
}
