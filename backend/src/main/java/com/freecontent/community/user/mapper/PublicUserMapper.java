package com.freecontent.community.user.mapper;
import com.freecontent.community.user.model.PublicUserProfile;
import org.apache.ibatis.annotations.*;
@Mapper
public interface PublicUserMapper {
    @Select("SELECT u.id,u.display_name,u.bio,u.created_at,a.id AS avatar_asset_id FROM app_user u LEFT JOIN media_asset a ON a.id=u.avatar_asset_id AND a.owner_id=u.id AND a.scene='AVATAR' AND a.state='READY' WHERE u.id=#{id}")
    PublicUserProfile findById(@Param("id")String id);
    @Select("""
        <script> SELECT u.id,u.display_name,u.bio,u.created_at,a.id AS avatar_asset_id FROM app_user u
        LEFT JOIN media_asset a ON a.id=u.avatar_asset_id AND a.owner_id=u.id AND a.scene='AVATAR' AND a.state='READY' WHERE u.id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")"> #{id} </foreach> </script>
        """)
    java.util.List<PublicUserProfile> findByIds(@Param("ids") java.util.List<String> ids);
}
