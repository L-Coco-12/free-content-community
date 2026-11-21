package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.ratelimit.ContentQueryRateLimiter;
import com.freecontent.community.content.workspace.dto.RevisionPage;
import com.freecontent.community.content.workspace.service.impl.ContentWorkspaceServiceImpl;
import com.freecontent.community.content.workspace.service.transaction.ContentWorkspaceTransactionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentRevisionHistoryServiceTest {
    private final ContentWorkspaceTransactionService transactions=mock(ContentWorkspaceTransactionService.class);
    private final ContentQueryRateLimiter limiter=mock(ContentQueryRateLimiter.class);
    private final ContentWorkspaceService service=new ContentWorkspaceServiceImpl(transactions,limiter);
    private final SessionPrincipal actor=new SessionPrincipal("session",CurrentUser.from(LoginTestSupport.account(),List.of(UserRole.USER)),"digest",1);
    @Test void invalidIdentityOrIdsCannotAcquireQuota(){
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->service.listMyRevisions("content",null,null,null,"source")).errorCode());
        for(String id:new String[]{null,"","bad!","a".repeat(65)})assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(BusinessException.class,()->service.listMyRevisions(id,null,null,actor,"source")).errorCode());
        verifyNoInteractions(limiter,transactions);
    }
    @ParameterizedTest @CsvSource({"page,0,VALIDATION_ERROR","page,-1,VALIDATION_ERROR","page,no,MALFORMED_REQUEST",
            "pageSize,0,VALIDATION_ERROR","pageSize,101,VALIDATION_ERROR","pageSize,999999999999999999999,VALIDATION_ERROR"})
    void invalidPaginationDoesNotConsumeQuota(String field,String value,ErrorCode code){
        var error=assertThrows(BusinessException.class,()->service.listMyRevisions("content",field.equals("page")?value:null,field.equals("pageSize")?value:null,actor,"source"));
        assertEquals(code,error.errorCode());assertEquals("query",error.violations().get(0).location());assertEquals(field,error.violations().get(0).path());verifyNoInteractions(limiter,transactions);
    }
    @Test void defaultsAndHugePageNumbersArePassedWithoutNarrowing(){
        var first=new RevisionPage(List.of(),BigInteger.ONE,20,0);when(transactions.listRevisions("content",BigInteger.ONE,20,actor)).thenReturn(first);
        assertSame(first,service.listMyRevisions("content",null,null,actor,"source"));
        var huge=BigInteger.TWO.pow(200);var result=new RevisionPage(List.of(),huge,100,0);when(transactions.listRevisions("content",huge,100,actor)).thenReturn(result);
        assertSame(result,service.listMyRevisions("content",huge.toString(),"100",actor,"source"));verify(limiter,times(2)).acquire(actor.user().id(),"source");
    }
    @Test void quotaFailureStopsTheReadTransaction(){
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire(actor.user().id(),"source");
        assertEquals(ErrorCode.RATE_LIMITED,assertThrows(BusinessException.class,()->service.listMyRevisions("content",null,null,actor,"source")).errorCode());verifyNoInteractions(transactions);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void databaseAndTransactionFailuresAre503WithoutInternalDetails(boolean transaction){
        RuntimeException failure=transaction?new CannotCreateTransactionException("private SQL detail"):new DataAccessResourceFailureException("private SQL detail");
        doThrow(failure).when(transactions).listRevisions(anyString(),any(),anyInt(),eq(actor));
        var error=assertThrows(BusinessException.class,()->service.listMyRevisions("content",null,null,actor,"source"));assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private"));
    }
}
