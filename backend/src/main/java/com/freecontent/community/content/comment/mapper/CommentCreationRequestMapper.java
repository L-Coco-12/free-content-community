package com.freecontent.community.content.comment.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CommentCreationRequestMapper {
    String BY_SCOPE = """
            SELECT fingerprint, comment_id, result_snapshot, lease_token, lease_until, expires_at
            FROM comment_creation_request WHERE created_by=#{actor} AND key_hash=#{key}
            """;

    @Select(BY_SCOPE)
    RequestRow find(@Param("actor") String actor, @Param("key") String keyHash);

    @Select(BY_SCOPE + "FOR UPDATE")
    RequestRow lock(@Param("actor") String actor, @Param("key") String keyHash);

    @Insert("""
            INSERT INTO comment_creation_request(created_by, key_hash, fingerprint, lease_token, lease_until, created_at, expires_at)
            VALUES(#{actor}, #{key}, #{fingerprint}, #{token}, #{until}, #{now}, #{expires})
            """)
    int insert(@Param("actor") String actor, @Param("key") String keyHash, @Param("fingerprint") String fingerprint,
               @Param("token") String leaseToken, @Param("until") LocalDateTime leaseUntil,
               @Param("now") LocalDateTime now, @Param("expires") LocalDateTime expiresAt);

    @Update("""
            UPDATE comment_creation_request SET lease_token=#{token}, lease_until=#{until}
            WHERE created_by=#{actor} AND key_hash=#{key} AND comment_id IS NULL
            """)
    int renew(@Param("actor") String actor, @Param("key") String keyHash,
              @Param("token") String leaseToken, @Param("until") LocalDateTime leaseUntil);

    @Update("""
            UPDATE comment_creation_request SET comment_id=#{commentId}, result_snapshot=#{snapshot},
                lease_token=NULL, lease_until=NULL
            WHERE created_by=#{actor} AND key_hash=#{key} AND comment_id IS NULL
                  AND lease_token=#{token} AND lease_until>#{now} AND expires_at>#{now}
            """)
    int complete(@Param("actor") String actor, @Param("key") String keyHash, @Param("token") String leaseToken,
                 @Param("commentId") String commentId, @Param("snapshot") String snapshot, @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM comment_creation_request WHERE created_by=#{actor} AND key_hash=#{key} AND expires_at<=#{now}
            """)
    int deleteExpired(@Param("actor") String actor, @Param("key") String keyHash, @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM comment_creation_request
            WHERE created_by=#{actor} AND key_hash=#{key} AND comment_id IS NULL AND lease_token=#{token}
            """)
    int release(@Param("actor") String actor, @Param("key") String keyHash, @Param("token") String leaseToken);

    record RequestRow(String fingerprint, String commentId, String resultSnapshot, String leaseToken,
                      LocalDateTime leaseUntil, LocalDateTime expiresAt) {
        public boolean isComplete() { return commentId != null; }
        public boolean hasLiveLease(LocalDateTime now) { return leaseUntil != null && leaseUntil.isAfter(now); }
    }
}
