package com.freecontent.community.notification.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.notification.cursor.NotificationCursor;
import com.freecontent.community.notification.dto.BatchReadResult;
import com.freecontent.community.notification.dto.NotificationPage;
import com.freecontent.community.notification.dto.NotificationView;
import com.freecontent.community.notification.dto.UnreadCount;
import com.freecontent.community.notification.mapper.NotificationReadMapper;
import com.freecontent.community.notification.mapper.NotificationWriteMapper;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyNotificationsTransactionService {
    private final AccountAccessService accounts;
    private final NotificationReadMapper notifications;
    private final NotificationWriteMapper writes;
    private final NotificationCursor cursors;
    private final Clock clock;

    public MyNotificationsTransactionService(AccountAccessService accounts, NotificationReadMapper notifications,
                                             NotificationWriteMapper writes,
                                             NotificationCursor cursors, Clock clock) {
        this.accounts = accounts;
        this.notifications = notifications;
        this.writes = writes;
        this.cursors = cursors;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchReadResult markBatchRead(List<String> notificationIds, SessionPrincipal principal) {
        String recipient = authorizeRecipient(principal, true);
        // Batch requests lock their shared rows in primary-key order before any update.
        if (writes.lockOwnedBatch(notificationIds, recipient).size() != notificationIds.size()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return new BatchReadResult(writes.markUnreadBatchAsRead(notificationIds, recipient, local(clock.instant())));
    }

    @Transactional
    public void markRead(String notificationId, SessionPrincipal principal) {
        String recipient = authorizeRecipient(principal, true);
        int changed = writes.markUnreadAsRead(notificationId, recipient, local(clock.instant()));
        if (changed == 0 && writes.lockOwned(notificationId, recipient) == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    @Transactional(readOnly = true)
    public NotificationPage list(NotificationCursor.Query query, SessionPrincipal principal) {
        String recipient = authorizeRecipient(principal, false);
        var rows = notifications.list(recipient, query.readState().name(), local(query.snapshotAt()),
                local(query.lastAt()), query.lastId(), query.limit() + 1);
        boolean more = rows.size() > query.limit();
        String next = null;
        if (more) {
            var last = rows.get(query.limit() - 1);
            next = cursors.next(query, instant(last.createdAt()), last.id());
        }
        var items = rows.stream().limit(query.limit()).map(row -> new NotificationView(
                row.id(), row.type(), row.title(), row.body(), row.targetType(), row.targetId(),
                instant(row.readAt()), instant(row.createdAt()))).toList();
        return new NotificationPage(items, next, more, query.snapshotAt());
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount(SessionPrincipal principal) {
        long count = notifications.countUnread(authorizeRecipient(principal, false));
        return new UnreadCount(count, clock.instant());
    }

    private String authorizeRecipient(SessionPrincipal principal, boolean requireActive) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled() || (requireActive && !account.active())) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        }
        if (!account.emailVerified()) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return account.id();
    }

    private LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
