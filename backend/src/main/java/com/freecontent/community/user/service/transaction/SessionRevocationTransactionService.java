package com.freecontent.community.user.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.service.AccountAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionRevocationTransactionService {
    private final AccountAccessService accounts;
    private final RedisSessionStore sessions;
    public SessionRevocationTransactionService(AccountAccessService accounts,RedisSessionStore sessions){this.accounts=accounts;this.sessions=sessions;}
    @Transactional(readOnly=true)
    public void revoke(String sessionId,SessionPrincipal actor){
        var account=accounts.findByIdForShare(actor.user().id());
        if(account==null || account.authVersion()==null || account.authVersion()!=actor.authVersion())throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if(account.disabled())throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if(!account.emailVerified())throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        sessions.revokeOwned(account.id(),account.authVersion(),actor.sessionId(),actor.tokenDigest(),sessionId);
    }
}
