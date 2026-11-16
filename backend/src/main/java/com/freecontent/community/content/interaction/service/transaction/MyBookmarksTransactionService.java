package com.freecontent.community.content.interaction.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.interaction.mapper.BookmarkQueryMapper;
import com.freecontent.community.content.interaction.cursor.BookmarkCursor;
import com.freecontent.community.content.interaction.model.BookmarkListQuery;
import com.freecontent.community.content.service.read.PublicContentSummaryReader;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MyBookmarksTransactionService {
    private final AccountAccessService accounts;
    private final BookmarkQueryMapper bookmarks;
    private final PublicContentSummaryReader summaries;
    private final BookmarkCursor cursors;
    public MyBookmarksTransactionService(AccountAccessService accounts,BookmarkQueryMapper bookmarks,PublicContentSummaryReader summaries,BookmarkCursor cursors){
        this.accounts=accounts;this.bookmarks=bookmarks;this.summaries=summaries;this.cursors=cursors;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ContentPageView list(BookmarkListQuery query,SessionPrincipal actor){
        var account=accounts.findByIdForShare(actor.user().id());
        if(account==null || account.authVersion()==null || account.authVersion()!=actor.authVersion())throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if(account.disabled())throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if(!account.emailVerified())throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        if(!account.id().equals(query.userId()))throw new BusinessException(ErrorCode.CURSOR_INVALID);
        var scanned=bookmarks.list(account.id(),local(query.snapshotAt()),local(query.lastAt()),query.lastId(),query.limit()+1);
        boolean more=scanned.size()>query.limit();
        var rows=scanned.subList(0,Math.min(query.limit(),scanned.size()));
        var items=summaries.readAll(rows.stream().map(BookmarkQueryMapper.BookmarkedRow::published).toList(),account.id());
        // 以最后扫描关系推进，即使投影过滤导致空页也不会卡在同一位置。
        String next=more?cursors.next(query,rows.get(rows.size()-1).bookmarkedAt().toInstant(ZoneOffset.UTC),rows.get(rows.size()-1).bookmarkId()):null;
        return new ContentPageView(items,next,more,query.snapshotAt());
    }
    private LocalDateTime local(Instant value){return value==null?null:LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
}
