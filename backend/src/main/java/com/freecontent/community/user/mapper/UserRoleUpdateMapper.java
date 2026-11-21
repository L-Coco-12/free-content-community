package com.freecontent.community.user.mapper;

import com.freecontent.community.user.model.UserRole;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserRoleUpdateMapper {
    @Insert("INSERT INTO app_user_role (user_id,role_code,created_at) VALUES (#{userId},#{role},#{now})")
    int add(@Param("userId") String userId, @Param("role") UserRole role, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM app_user_role WHERE user_id=#{userId} AND role_code=#{role}")
    int remove(@Param("userId") String userId, @Param("role") UserRole role);

    @Update("""
            UPDATE app_user SET version=version+1, auth_version=auth_version+1, updated_at=#{now}
            WHERE id=#{userId} AND version=#{version}
            """)
    int advanceVersions(@Param("userId") String userId, @Param("version") int version, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO user_role_change_audit
                (id,user_id,operator_id,before_roles,after_roles,before_version,after_version,reason,request_id,occurred_at)
            VALUES (#{id},#{userId},#{operatorId},#{beforeRoles},#{afterRoles},#{beforeVersion},#{afterVersion},
                    #{reason},#{requestId},#{occurredAt})
            """)
    int insertAudit(Audit audit);

    record Audit(String id, String userId, String operatorId, String beforeRoles, String afterRoles,
                 int beforeVersion, int afterVersion, String reason, String requestId, LocalDateTime occurredAt) { }
}
