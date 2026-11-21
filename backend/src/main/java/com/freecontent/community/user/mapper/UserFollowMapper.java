package com.freecontent.community.user.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserFollowMapper {
    @Insert("""
            INSERT INTO user_follow(id, follower_id, followed_id, created_at)
            VALUES(#{id}, #{follower}, #{target}, #{createdAt})
            """)
    int insert(@Param("id") String id, @Param("follower") String follower, @Param("target") String target,
               @Param("createdAt") LocalDateTime createdAt);

    @Select("SELECT id FROM user_follow WHERE follower_id=#{follower} AND followed_id=#{target} FOR SHARE")
    String findId(@Param("follower") String follower, @Param("target") String target);

    @Delete("DELETE FROM user_follow WHERE follower_id=#{follower} AND followed_id=#{target}")
    void delete(@Param("follower") String follower, @Param("target") String target);

    @Select("""
            <script>
            SELECT id, followed_id, created_at
            FROM user_follow
            WHERE follower_id=#{follower} AND created_at &lt;= #{snapshot}
            <if test="lastAt != null">
              AND (created_at &lt; #{lastAt} OR (created_at=#{lastAt} AND id &lt; #{lastId}))
            </if>
            ORDER BY created_at DESC, id DESC
            LIMIT #{take}
            </script>
            """)
    List<FollowedRow> list(@Param("follower") String follower, @Param("snapshot") LocalDateTime snapshot,
                          @Param("lastAt") LocalDateTime lastAt, @Param("lastId") String lastId, @Param("take") int take);

    record FollowedRow(String id, String followedId, LocalDateTime createdAt) { }
}
