package com.freecontent.community.content.browse.service.transaction;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.content.browse.dto.*;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.browse.mapper.PublicContentMapper.*;
import com.freecontent.community.content.browse.model.PublicContentListQuery;
import com.freecontent.community.user.service.read.PublicUserReader;
import com.freecontent.community.content.service.read.PublicContentSummaryReader;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 键集扫描与批量投影处于同一只读快照；游标按已扫描位置推进，避免跳过异常条目后提前结束。 */
@Service
public class PublicContentListTransactionService {
    private final PublicContentMapper contents;
    private final PublicUserReader users;
    private final PublicContentCursor cursors;
    private final PublicContentSummaryReader summaries;
    public PublicContentListTransactionService(PublicContentMapper contents, PublicUserReader users, PublicContentCursor cursors, PublicContentSummaryReader summaries) {
        this.contents = contents; this.users = users; this.cursors = cursors; this.summaries = summaries;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentPageView list(PublicContentListQuery query, String viewerId) {
        var author = query.authorId() == null ? null : users.read(query.authorId());
        if (query.authorId() != null && author == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var scanned = query.authorId() == null
                ? contents.listVisible(query.kind(), query.tagId(), local(query.snapshotAt()), local(query.lastFirstPublishedAt()), query.lastId(), query.limit() + 1)
                : contents.listVisibleByAuthor(query.authorId(), local(query.snapshotAt()), local(query.lastFirstPublishedAt()), query.lastId(), query.limit() + 1);
        boolean more = scanned.size() > query.limit();
        var rows = scanned.subList(0, Math.min(query.limit(), scanned.size()));
        if (rows.isEmpty()) return new ContentPageView(List.of(), null, false, query.snapshotAt());
        var items = summaries.readAll(rows, viewerId, query.authorId() == null ? null : Map.of(query.authorId(), author));
        var last = rows.get(rows.size() - 1);
        String next = more ? cursors.next(query, last.firstPublishedAt().toInstant(ZoneOffset.UTC), last.id()) : null;
        return new ContentPageView(items, next, more, query.snapshotAt());
    }
    private LocalDateTime local(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
