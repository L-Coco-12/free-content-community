package com.freecontent.community.notification.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.notification.cursor.NotificationCursor;
import com.freecontent.community.notification.cursor.NotificationCursor.ReadState;
import com.freecontent.community.notification.dto.NotificationPage;
import com.freecontent.community.notification.dto.UnreadCount;
import com.freecontent.community.notification.ratelimit.NotificationQueryRateLimiter;
import com.freecontent.community.notification.service.MyNotificationsService;
import com.freecontent.community.notification.service.transaction.MyNotificationsTransactionService;
import java.math.BigInteger;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class MyNotificationsServiceImpl implements MyNotificationsService {
    private final NotificationCursor cursors;
    private final NotificationQueryRateLimiter limiter;
    private final MyNotificationsTransactionService transactions;

    public MyNotificationsServiceImpl(NotificationCursor cursors, NotificationQueryRateLimiter limiter,
                                      MyNotificationsTransactionService transactions) {
        this.cursors = cursors;
        this.limiter = limiter;
        this.transactions = transactions;
    }

    @Override
    public NotificationPage list(String cursor, String limit, String readState, SessionPrincipal principal, String sourceIp) {
        String recipient = requireRecipient(principal);
        int size = parseLimit(limit);
        if (cursor != null && cursor.codePointCount(0, cursor.length()) > 4096) {
            throw invalid(ErrorCode.VALIDATION_ERROR, "cursor", "游标不能超过4096个字符");
        }
        var query = cursors.open(cursor, recipient, parseState(readState), size);
        limiter.acquire(recipient, sourceIp);
        try {
            return transactions.list(query, principal);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "通知查询数据服务暂不可用");
        }
    }

    @Override
    public UnreadCount unreadCount(SessionPrincipal principal, String sourceIp) {
        String recipient = requireRecipient(principal);
        limiter.acquire(recipient, sourceIp);
        try {
            return transactions.unreadCount(principal);
        } catch (DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "未读通知数查询数据服务暂不可用");
        }
    }

    private String requireRecipient(SessionPrincipal principal) {
        if (principal == null || principal.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.user().id();
    }

    private int parseLimit(String value) {
        if (value == null) return 20;
        if (!value.matches("-?[0-9]+")) throw invalid(ErrorCode.MALFORMED_REQUEST, "limit", "必须为整数");
        var parsed = new BigInteger(value);
        if (parsed.signum() <= 0 || parsed.compareTo(BigInteger.valueOf(50)) > 0) {
            throw invalid(ErrorCode.VALIDATION_ERROR, "limit", "必须在1到50之间");
        }
        return parsed.intValueExact();
    }

    private ReadState parseState(String value) {
        if (value == null) return ReadState.ALL;
        try {
            return ReadState.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw invalid(ErrorCode.VALIDATION_ERROR, "readState", "仅允许ALL、UNREAD、READ");
        }
    }

    private BusinessException invalid(ErrorCode code, String field, String reason) {
        return new BusinessException(code, "通知查询参数不合法", null, null,
                List.of(ProblemDetailResponse.Violation.query(field, reason)));
    }
}
