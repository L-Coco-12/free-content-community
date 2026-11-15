package com.freecontent.community.user.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface UserAvatarMapper {
    record Binding(String userId,String assetId) { }
    @Select("SELECT id AS user_id,avatar_asset_id AS asset_id FROM app_user WHERE id=#{id} FOR SHARE")
    Binding lockBinding(String id);
}
