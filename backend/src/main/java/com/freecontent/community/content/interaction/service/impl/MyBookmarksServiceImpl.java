package com.freecontent.community.content.interaction.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.common.pagination.CursorPageParameters;
import com.freecontent.community.content.interaction.ratelimit.BookmarkQueryRateLimiter;
import com.freecontent.community.content.interaction.cursor.BookmarkCursor;
import com.freecontent.community.content.interaction.service.MyBookmarksService;
import com.freecontent.community.content.interaction.service.transaction.MyBookmarksTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class MyBookmarksServiceImpl implements MyBookmarksService {
    private final BookmarkQueryRateLimiter limiter;
    private final MyBookmarksTransactionService transactions;
    private final BookmarkCursor cursors;
    public MyBookmarksServiceImpl(BookmarkQueryRateLimiter limiter,MyBookmarksTransactionService transactions,BookmarkCursor cursors){this.limiter=limiter;this.transactions=transactions;this.cursors=cursors;}
    @Override public ContentPageView list(String cursor,String limit,SessionPrincipal actor,String sourceIp){
        if(actor==null || actor.user()==null)throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        int size=CursorPageParameters.pageSize(limit);
        CursorPageParameters.requireCursorLength(cursor);
        var query=cursors.open(cursor,size,actor.user().id());
        limiter.acquire(actor.user().id(),sourceIp);
        try{return transactions.list(query,actor);}
        catch(DataAccessException | TransactionException ex){throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"收藏列表数据服务暂不可用");}
    }
}
