package com.freecontent.community.user.mapper;

import com.freecontent.community.user.model.PublicUserProfile;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PublicUserMapper {
    String PUBLIC_PROFILE = """
            SELECT u.id, u.display_name, u.bio, u.created_at, a.id AS avatar_asset_id,
                   (SELECT COUNT(*) FROM user_follow f WHERE f.followed_id=u.id) AS follower_count
            FROM app_user u
            LEFT JOIN media_asset a ON a.id=u.avatar_asset_id AND a.owner_id=u.id AND a.scene='AVATAR' AND a.state='READY'
            """;

    @Select(PUBLIC_PROFILE + "WHERE u.id=#{id}")
    PublicUserProfile findById(@Param("id") String id);

    @Select("<script>" + PUBLIC_PROFILE + """
            WHERE u.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")"> #{id} </foreach>
            </script>
            """)
    List<PublicUserProfile> findByIds(@Param("ids") List<String> ids);
}
