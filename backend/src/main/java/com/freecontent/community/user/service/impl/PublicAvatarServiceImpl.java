package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.user.ratelimit.AvatarAccessRateLimiter;
import com.freecontent.community.user.service.PublicAvatarService;
import com.freecontent.community.user.service.transaction.PublicAvatarTransactionService;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class PublicAvatarServiceImpl implements PublicAvatarService {
    private final PublicAvatarTransactionService transactions;
    private final MediaObjectReader reader;
    private final AvatarAccessRateLimiter limiter;
    public PublicAvatarServiceImpl(PublicAvatarTransactionService transactions,MediaObjectReader reader,AvatarAccessRateLimiter limiter) {
        this.transactions=transactions;this.reader=reader;this.limiter=limiter;
    }
    @Override public MediaAccess getAvatar(String userId,String assetId,SessionPrincipal viewer,String sourceIp) {
        valid(userId,"path","userId");valid(assetId,"query","assetId");
        limiter.acquire(viewer==null?null:viewer.user().id(),sourceIp);
        try {
            var proof=transactions.prepare(userId,assetId);
            reader.verify(proof.object());
            return transactions.finish(userId,assetId,proof);
        } catch(DataAccessException | TransactionException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"头像读取数据服务暂不可用");
        }
    }
    private void valid(String value,String location,String field) {
        if(value==null || !value.matches("[A-Za-z0-9_-]{1,64}"))throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "头像读取参数不合法",null,null,List.of(new ProblemDetailResponse.Violation(location,field,"仅允许1到64位字母、数字、下划线及短横线")));
    }
}
