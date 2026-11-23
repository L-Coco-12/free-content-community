package com.freecontent.community.report.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReportSubmissionRequestMapper {
    String BY_SCOPE = """
            SELECT fingerprint, report_id, lease_token, lease_until, expires_at
            FROM report_submission_request WHERE reporter_id=#{actor} AND key_hash=#{key}
            """;

    @Select(BY_SCOPE)
    RequestRow find(@Param("actor") String actor, @Param("key") String keyHash);

    @Select(BY_SCOPE + "FOR UPDATE")
    RequestRow lock(@Param("actor") String actor, @Param("key") String keyHash);

    @Insert("""
            INSERT INTO report_submission_request(reporter_id, key_hash, fingerprint, lease_token, lease_until, created_at, expires_at)
            VALUES(#{actor}, #{key}, #{fingerprint}, #{token}, #{until}, #{now}, #{expires})
            """)
    int insert(@Param("actor") String actor, @Param("key") String keyHash, @Param("fingerprint") String fingerprint,
               @Param("token") String leaseToken, @Param("until") LocalDateTime leaseUntil,
               @Param("now") LocalDateTime now, @Param("expires") LocalDateTime expiresAt);

    @Update("""
            UPDATE report_submission_request SET lease_token=#{token}, lease_until=#{until}
            WHERE reporter_id=#{actor} AND key_hash=#{key} AND report_id IS NULL
            """)
    int renew(@Param("actor") String actor, @Param("key") String keyHash,
              @Param("token") String leaseToken, @Param("until") LocalDateTime leaseUntil);

    @Update("""
            UPDATE report_submission_request SET report_id=#{reportId},
                lease_token=NULL, lease_until=NULL
            WHERE reporter_id=#{actor} AND key_hash=#{key} AND report_id IS NULL
                  AND lease_token=#{token} AND lease_until>#{now} AND expires_at>#{now}
            """)
    int complete(@Param("actor") String actor, @Param("key") String keyHash, @Param("token") String leaseToken,
                 @Param("reportId") String reportId, @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM report_submission_request WHERE reporter_id=#{actor} AND key_hash=#{key} AND expires_at<=#{now}
            """)
    int deleteExpired(@Param("actor") String actor, @Param("key") String keyHash, @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM report_submission_request
            WHERE reporter_id=#{actor} AND key_hash=#{key} AND report_id IS NULL AND lease_token=#{token}
            """)
    int release(@Param("actor") String actor, @Param("key") String keyHash, @Param("token") String leaseToken);

    record RequestRow(String fingerprint, String reportId, String leaseToken,
                      LocalDateTime leaseUntil, LocalDateTime expiresAt) {
        public boolean isComplete() { return reportId != null; }
        public boolean hasLiveLease(LocalDateTime now) { return leaseUntil != null && leaseUntil.isAfter(now); }
    }
}
