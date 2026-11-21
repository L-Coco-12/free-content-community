package com.freecontent.community.user.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.session.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.*;
import com.freecontent.community.user.service.AccountAccessService;
import java.util.Comparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MySessionsTransactionService {
    private final AccountAccessService accounts;
    private final RedisSessionStore sessions;
    public MySessionsTransactionService(AccountAccessService accounts,RedisSessionStore sessions){this.accounts=accounts;this.sessions=sessions;}
    @Transactional(readOnly=true)
    public SessionListView list(SessionPrincipal actor){
        var account=accounts.findByIdForShare(actor.user().id());
        if(account==null || account.authVersion()==null || account.authVersion()!=actor.authVersion())throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if(account.disabled())throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if(!account.emailVerified())throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        var rows=sessions.listActive(account.id(),account.authVersion(),actor.sessionId(),actor.tokenDigest());
        return new SessionListView(rows.stream().sorted(Comparator.comparing(SessionMetadata::createdAt).thenComparing(SessionMetadata::id).reversed())
                .map(row->new SessionView(row.id(),row.createdAt(),row.expiresAt(),row.lastSeenAt(),row.id().equals(actor.sessionId()),row.deviceLabel())).toList());
    }
}
