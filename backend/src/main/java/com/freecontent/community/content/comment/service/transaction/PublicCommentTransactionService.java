package com.freecontent.community.content.comment.service.transaction;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.comment.cursor.ContentCommentsCursor;
import com.freecontent.community.content.comment.cursor.ContentCommentsCursor.Query;
import com.freecontent.community.content.comment.dto.CommentPage;
import com.freecontent.community.content.comment.dto.CommentView;
import com.freecontent.community.content.comment.mapper.CommentMapper;
import com.freecontent.community.content.comment.mapper.CommentMapper.PublicCommentRow;
import com.freecontent.community.content.comment.service.read.CommentAuthorReader;
import com.freecontent.community.user.dto.PublicUserView;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicCommentTransactionService {
    private final CommentMapper comments;
    private final CommentAuthorReader authors;
    private final PublicContentMapper contents;
    private final ContentCommentsCursor cursors;

    public PublicCommentTransactionService(CommentMapper comments, CommentAuthorReader authors,
                                           PublicContentMapper contents, ContentCommentsCursor cursors) {
        this.comments = comments;
        this.authors = authors;
        this.contents = contents;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CommentView read(String id) {
        var row = comments.findPublic(id);
        if (row == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);

        if (!isVisible(row) && (row.rootCommentId() != null || row.replyCount() == 0)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return view(row, isVisible(row) ? authors.read(row.authorId()) : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CommentPage list(Query query) {
        var rows = switch (query.kind()) {
            case ROOTS -> roots(query);
            case REPLIES -> replies(query);
        };
        boolean hasMore = rows.size() > query.limit();
        var pageRows = rows.subList(0, Math.min(rows.size(), query.limit()));
        var authorIds = pageRows.stream().filter(PublicCommentTransactionService::isVisible)
                .map(PublicCommentRow::authorId).distinct().toList();
        var profiles = authors.readAll(authorIds);
        var items = pageRows.stream().map(row -> view(row, isVisible(row) ? profiles.get(row.authorId()) : null)).toList();
        String nextCursor = null;
        if (hasMore) {
            var last = pageRows.get(pageRows.size() - 1);
            nextCursor = cursors.next(query, last.createdAt().toInstant(ZoneOffset.UTC), last.id());
        }
        return new CommentPage(items, nextCursor, hasMore, query.snapshotAt());
    }

    private List<PublicCommentRow> roots(Query query) {
        String contentId = query.resourceId();
        if (!contents.existsVisible(contentId)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return comments.listPublicRoots(contentId, utc(query.snapshotAt()), utc(query.lastAt()),
                query.lastId(), query.limit() + 1);
    }

    private List<PublicCommentRow> replies(Query query) {
        String rootId = query.resourceId();
        String contentId = comments.findPublicThreadContentId(rootId);
        if (contentId == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return comments.listPublicReplies(contentId, rootId, utc(query.snapshotAt()), utc(query.lastAt()),
                query.lastId(), query.limit() + 1);
    }

    private static CommentView view(PublicCommentRow row, PublicUserView author) {
        boolean visible = isVisible(row);
        if (visible && author == null) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "评论作者公开资料暂不可用");
        }
        return new CommentView(row.id(), row.contentId(), row.rootCommentId(), row.replyToCommentId(),
                visible ? author : null, visible ? row.body() : null,
                visible ? "VISIBLE" : "DELETED_PLACEHOLDER", row.replyCount(), row.createdAt().toInstant(ZoneOffset.UTC));
    }

    private static boolean isVisible(PublicCommentRow row) {
        return "VISIBLE".equals(row.state());
    }

    private static LocalDateTime utc(Instant time) {
        return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }
}
