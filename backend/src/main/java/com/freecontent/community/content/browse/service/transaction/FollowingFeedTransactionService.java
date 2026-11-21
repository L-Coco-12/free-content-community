package com.freecontent.community.content.browse.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.browse.model.PublicContentListQuery;
import com.freecontent.community.content.service.read.PublicContentSummaryReader;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FollowingFeedTransactionService {
    private final AccountAccessService accounts;
    private final PublicContentMapper contents;
    private final PublicContentSummaryReader summaries;
    private final PublicContentCursor cursors;

    public FollowingFeedTransactionService(AccountAccessService accounts, PublicContentMapper contents,
                                           PublicContentSummaryReader summaries, PublicContentCursor cursors) {
        this.accounts = accounts;
        this.contents = contents;
        this.summaries = summaries;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentPageView list(PublicContentListQuery query, SessionPrincipal actor) {
        String viewerId = authorize(actor);
        if (!viewerId.equals(query.scope())) throw new BusinessException(ErrorCode.CURSOR_INVALID);
        var scanned = contents.listVisibleFromFollowedAuthors(viewerId, utc(query.snapshotAt()),
                utc(query.lastFirstPublishedAt()), query.lastId(), query.limit() + 1);
        boolean hasMore = scanned.size() > query.limit();
        var rows = scanned.subList(0, Math.min(scanned.size(), query.limit()));
        var items = summaries.readAll(rows, viewerId);
        String nextCursor = null;
        if (hasMore) {
            // 按扫描位置推进，即使本页部分卡片被投影校验过滤也可以继续。
            var last = rows.get(rows.size() - 1);
            nextCursor = cursors.nextForFollowing(query, last.firstPublishedAt().toInstant(ZoneOffset.UTC), last.id());
        }
        return new ContentPageView(items, nextCursor, hasMore, query.snapshotAt());
    }

    private String authorize(SessionPrincipal actor) {
        var account = accounts.findByIdForShare(actor.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != actor.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified()) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return account.id();
    }

    private static LocalDateTime utc(Instant time) {
        return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }
}
