package com.freecontent.community.user.service.transaction;

import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.media.storage.*;
import com.freecontent.community.user.config.AvatarAccessProperties;
import com.freecontent.community.user.mapper.UserAvatarMapper;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 用户头像指针→素材共享锁；两次短事务之间的 HEAD 不持有数据库锁。 */
@Service
public class PublicAvatarTransactionService {
    private final UserAvatarMapper users;
    private final MediaAssetMapper assets;
    private final StorageProperties storage;
    private final MediaReadSigner signer;
    private final AvatarAccessProperties properties;
    private final Clock clock;
    public PublicAvatarTransactionService(UserAvatarMapper users,MediaAssetMapper assets,StorageProperties storage,
            MediaReadSigner signer,AvatarAccessProperties properties,Clock clock) {
        this.users=users;this.assets=assets;this.storage=storage;this.signer=signer;this.properties=properties;this.clock=clock;
    }
    public record Proof(String userId,String assetId,MediaObjectReader.ObjectReference object) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Proof prepare(String userId,String assetId) {return proof(userId,assetId);}
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public MediaAccess finish(String userId,String assetId,Proof checked) {
        var current=proof(userId,assetId);
        if(!current.equals(checked)) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"头像读取绑定已变化，请重试");
        return signer.sign(current.object(),clock.instant(),properties.readUrlTtlSeconds());
    }
    private Proof proof(String userId,String assetId) {
        var binding=users.lockBinding(userId);
        if(binding==null || !assetId.equals(binding.assetId()))throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var asset=assets.findOwnedForShare(assetId,userId);
        if(asset==null || !"AVATAR".equals(asset.getScene()))throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if(!"READY".equals(asset.getState()))throw new BusinessException(ErrorCode.MEDIA_NOT_READY);
        return new Proof(userId,assetId,MediaObjectReader.ObjectReference.fromAsset(assetId,userId,asset,storage));
    }
}
