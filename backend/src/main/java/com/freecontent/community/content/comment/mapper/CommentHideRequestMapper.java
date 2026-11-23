package com.freecontent.community.content.comment.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CommentHideRequestMapper {
    String BY_SCOPE = """
            SELECT fingerprint, action_id, lease_token, lease_until, expires_at
            FROM comment_hide_request WHERE operator_id=#{operator} AND key_hash=#{key}
            """;

    @Select(BY_SCOPE)
    RequestRow find(@Param("operator") String operator, @Param("key") String keyHash);

    @Select(BY_SCOPE + "FOR UPDATE")
    RequestRow lock(@Param("operator") String operator, @Param("key") String keyHash);

    @Insert("""
            INSERT INTO comment_hide_request(operator_id, key_hash, fingerprint, lease_token, lease_until, created_at, expires_at)
            VALUES(#{operator}, #{key}, #{fingerprint}, #{token}, #{until}, #{now}, #{expires})
            """)
    int insert(@Param("operator") String operator, @Param("key") String keyHash, @Param("fingerprint") String fingerprint,
               @Param("token") String leaseToken, @Param("until") LocalDateTime leaseUntil,
               @Param("now") LocalDateTime now, @Param("expires") LocalDateTime expiresAt);

    @Update("""
            UPDATE comment_hide_request SET lease_token=#{token}, lease_until=#{until}
            WHERE operator_id=#{operator} AND key_hash=#{key} AND action_id IS NULL
            """)
    int renew(@Param("operator") String operator, @Param("key") String keyHash,
              @Param("token") String leaseToken, @Param("until") LocalDateTime leaseUntil);

    @Update("""
            UPDATE comment_hide_request SET action_id=#{action}, lease_token=NULL, lease_until=NULL
            WHERE operator_id=#{operator} AND key_hash=#{key} AND action_id IS NULL
                AND lease_token=#{token} AND lease_until>#{now} AND expires_at>#{now}
            """)
    int complete(@Param("operator") String operator, @Param("key") String keyHash, @Param("token") String leaseToken,
                 @Param("action") String actionId, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM comment_hide_request WHERE operator_id=#{operator} AND key_hash=#{key} AND expires_at<=#{now}")
    int deleteExpired(@Param("operator") String operator, @Param("key") String keyHash, @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM comment_hide_request
            WHERE operator_id=#{operator} AND key_hash=#{key} AND action_id IS NULL AND lease_token=#{token}
            """)
    int release(@Param("operator") String operator, @Param("key") String keyHash, @Param("token") String leaseToken);

    record RequestRow(String fingerprint, String actionId, String leaseToken, LocalDateTime leaseUntil, LocalDateTime expiresAt) {
        public boolean isComplete() { return actionId != null; }
        public boolean hasLiveLease(LocalDateTime now) { return leaseUntil != null && leaseUntil.isAfter(now); }
    }
}
