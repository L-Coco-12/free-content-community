package com.freecontent.community.content.interaction.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.interaction.mapper.ContentBookmarkMapper;
import com.freecontent.community.content.service.read.PublicContentVisibilityReader;
import com.freecontent.community.user.service.AccountAccessService;
import java.time.*;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ContentBookmarkTransactionService {
    private final AccountAccessService accounts;
    private final PublicContentVisibilityReader visibility;
    private final ContentBookmarkMapper bookmarks;
    private final Clock clock;
    public ContentBookmarkTransactionService(AccountAccessService accounts,PublicContentVisibilityReader visibility,ContentBookmarkMapper bookmarks,Clock clock){
        this.accounts=accounts;this.visibility=visibility;this.bookmarks=bookmarks;this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void bookmark(String contentId,SessionPrincipal actor){
        String user=actor.user().id();var account=accounts.findByIdForShare(user);
        if(account==null || account.authVersion()==null || account.authVersion()!=actor.authVersion())throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if(!account.active())throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if(!account.emailVerified())throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        visibility.requireCurrentlyVisible(contentId);
        try{
            if(bookmarks.insert(UUID.randomUUID().toString().replace("-",""),user,contentId,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC))!=1)
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"收藏数据暂不可用");
        }catch(DuplicateKeyException duplicate){
            // 表同时存在ID主键与用户/作品唯一键；不能把随机ID碰撞误报为已收藏。
            if(bookmarks.findExisting(user,contentId)==null)throw duplicate;
        }
    }
}
