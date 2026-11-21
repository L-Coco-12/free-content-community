package com.freecontent.community.notification.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.notification.cursor.NotificationCursor;
import com.freecontent.community.notification.dto.NotificationPage;
import com.freecontent.community.notification.dto.NotificationView;
import com.freecontent.community.notification.dto.UnreadCount;
import com.freecontent.community.notification.mapper.NotificationReadMapper;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyNotificationsTransactionService {
    private final AccountAccessService accounts;
    private final NotificationReadMapper notifications;
    private final NotificationCursor cursors;
    private final Clock clock;

    public MyNotificationsTransactionService(AccountAccessService accounts, NotificationReadMapper notifications,
                                             NotificationCursor cursors, Clock clock) {
        this.accounts = accounts;
        this.notifications = notifications;
        this.cursors = cursors;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationPage list(NotificationCursor.Query query, SessionPrincipal principal) {
        String recipient = authorizeRecipient(principal);
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
        long count = notifications.countUnread(authorizeRecipient(principal));
        return new UnreadCount(count, clock.instant());
    }

    private String authorizeRecipient(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
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
