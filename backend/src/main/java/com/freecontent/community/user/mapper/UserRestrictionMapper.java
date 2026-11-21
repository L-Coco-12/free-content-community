package com.freecontent.community.user.mapper;

import com.freecontent.community.user.entity.UserAccount.Status;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserRestrictionMapper {
    @Update("""
            UPDATE app_user SET account_status=#{status}, version=version+1,
                                auth_version=#{authVersion}, updated_at=#{now}
            WHERE id=#{userId} AND version=#{expectedVersion}
            """)
    int update(@Param("userId") String userId, @Param("status") Status status,
               @Param("expectedVersion") int expectedVersion, @Param("authVersion") long authVersion,
               @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO user_restriction_audit
                (id,user_id,operator_id,before_status,after_status,before_version,after_version,reason,request_id,occurred_at)
            VALUES (#{id},#{userId},#{operatorId},#{beforeStatus},#{afterStatus},#{beforeVersion},#{afterVersion},
                    #{reason},#{requestId},#{occurredAt})
            """)
    int insertAudit(Audit audit);

    record Audit(String id, String userId, String operatorId, Status beforeStatus, Status afterStatus,
                 int beforeVersion, int afterVersion, String reason, String requestId, LocalDateTime occurredAt) { }
}
