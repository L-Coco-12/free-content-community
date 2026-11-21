package com.freecontent.community.user.mapper;

import com.freecontent.community.user.entity.UserAccount.Status;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminAccountChangeMapper {
    @Select("SELECT id FROM admin_account_change_guard WHERE id=1 FOR UPDATE")
    Integer lockAdminAccountChanges();

    @Select("""
            SELECT account_status, version, auth_version, email_verified
            FROM app_user WHERE id=#{userId} FOR UPDATE
            """)
    Target lockTarget(@Param("userId") String userId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM app_user_role r JOIN app_user u ON u.id=r.user_id
                WHERE r.role_code='ADMIN' AND u.id<>#{userId}
                  AND u.account_status='ACTIVE' AND u.email_verified=TRUE
            )
            """)
    boolean hasOtherEffectiveAdmin(@Param("userId") String userId);

    record Target(Status accountStatus, int version, long authVersion, boolean emailVerified) {
        public boolean eligibleForAdminWrites() {
            return accountStatus == Status.ACTIVE && emailVerified;
        }
    }
}
