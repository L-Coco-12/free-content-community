package com.freecontent.community.content.interaction.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.interaction.mapper.ContentLikeMapper;
import com.freecontent.community.content.service.read.PublicContentVisibilityReader;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 账号→内容共享锁→关系→统计行；点赞额外校验公开修订与素材，取消只清理本人事实。 */
@Service
public class ContentLikeTransactionService {
    private final AccountAccessService accounts;
    private final PublicContentVisibilityReader visibility;
    private final ContentLikeMapper likes;
    private final Clock clock;
    public ContentLikeTransactionService(AccountAccessService accounts,PublicContentVisibilityReader visibility,ContentLikeMapper likes,Clock clock){
        this.accounts=accounts;this.visibility=visibility;this.likes=likes;this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void like(String contentId,SessionPrincipal principal) {
        String userId=authorize(principal);
        visibility.requireCurrentlyVisible(contentId);
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        try {
            if(likes.insert(userId,contentId,now)!=1)throw unavailable();
        } catch(DuplicateKeyException duplicate) { return; }
        if(likes.increment(contentId,now)<1)throw unavailable();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void unlike(String contentId,SessionPrincipal principal) {
        String userId=authorize(principal);
        // 保留与点赞/物理清理一致的父行锁序，但不读取或判断公开状态。
        if(likes.lockTarget(contentId)==null)return;
        int removed=likes.delete(userId,contentId);
        if(removed==0)return;
        if(removed!=1 || likes.decrement(contentId,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC))!=1)throw unavailable();
    }
    private String authorize(SessionPrincipal principal) {
        String userId=principal.user().id();
        var account=accounts.findByIdForShare(userId);
        if(account==null || account.authVersion()==null || account.authVersion()!=principal.authVersion())throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if(!account.active())throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if(!account.emailVerified())throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return userId;
    }
    private BusinessException unavailable(){return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"点赞数据暂不可用");}
}
