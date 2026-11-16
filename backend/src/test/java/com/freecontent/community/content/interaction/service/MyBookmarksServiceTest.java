package com.freecontent.community.content.interaction.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.interaction.cursor.BookmarkCursor;
import com.freecontent.community.content.interaction.ratelimit.BookmarkQueryRateLimiter;
import com.freecontent.community.content.interaction.service.impl.MyBookmarksServiceImpl;
import com.freecontent.community.content.interaction.service.transaction.MyBookmarksTransactionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MyBookmarksServiceTest {
    private final MyBookmarksTransactionService transactions=mock(MyBookmarksTransactionService.class);
    private final BookmarkQueryRateLimiter limiter=mock(BookmarkQueryRateLimiter.class);
    private final BookmarkCursor cursors=new BookmarkCursor(new PublicContentCursorProperties(900,Base64.getEncoder().encodeToString(new byte[32])),JsonMapper.builder().build(),Clock.systemUTC());
    private final MyBookmarksService service=new MyBookmarksServiceImpl(limiter,transactions,cursors);
    private final SessionPrincipal actor=new SessionPrincipal("session",CurrentUser.from(LoginTestSupport.account(),List.of(UserRole.USER)),"digest",1);
    @Test void missingAuthenticationDoesNotTouchQuotaOrData(){
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED,assertThrows(BusinessException.class,()->service.list(null,null,null,"source")).errorCode());verifyNoInteractions(limiter,transactions);
    }
    @Test void failedQuotaPreventsStartingTheReadTransaction(){
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(limiter).acquire(actor.user().id(),"source");
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(BusinessException.class,()->service.list(null,null,actor,"source")).errorCode());verifyNoInteractions(transactions);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void databaseAndTransactionFailuresReturn503WithoutInternalDetails(boolean transaction){
        RuntimeException failure=transaction?new CannotCreateTransactionException("private connection detail"):new DataAccessResourceFailureException("private connection detail");
        doThrow(failure).when(transactions).list(any(),eq(actor));
        var error=assertThrows(BusinessException.class,()->service.list(null,null,actor,"source"));assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,error.errorCode());assertFalse(error.getMessage().contains("private"));
    }
}
