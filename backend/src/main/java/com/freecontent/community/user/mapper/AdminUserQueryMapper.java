package com.freecontent.community.user.mapper;

import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.model.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminUserQueryMapper {
    String USER_COLUMNS = """
            id, display_name,
            CASE WHEN LOCATE('@', email) BETWEEN 2 AND CHAR_LENGTH(email) - 1
                      AND LOCATE('@', email, LOCATE('@', email) + 1) = 0
                 THEN CONCAT('***@', SUBSTRING_INDEX(email, '@', -1))
                 ELSE '***' END AS masked_email,
            email_verified, account_status, version, created_at
            """;

    String FILTER = """
            FROM app_user
            <where>
                <if test="userId != null"> AND id=#{userId} </if>
                <if test="status != null"> AND account_status=#{status} </if>
            </where>
            """;

    @Select("<script> SELECT COUNT(*) " + FILTER + " </script>")
    long count(@Param("userId") String userId, @Param("status") UserAccount.Status status);

    @Select("SELECT " + USER_COLUMNS + " FROM app_user WHERE id=#{userId}")
    UserRow findById(@Param("userId") String userId);

    @Select("<script> SELECT " + USER_COLUMNS + FILTER + """
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<UserRow> page(@Param("userId") String userId, @Param("status") UserAccount.Status status,
                       @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT user_id, role_code FROM app_user_role WHERE user_id IN
            <foreach collection="userIds" item="id" open="(" separator="," close=")"> #{id} </foreach>
            ORDER BY user_id, role_code
            </script>
            """)
    List<RoleRow> findRolesForUsers(@Param("userIds") List<String> userIds);

    record UserRow(String id, String displayName, String maskedEmail, boolean emailVerified,
                   UserAccount.Status accountStatus, int version, LocalDateTime createdAt) { }

    record RoleRow(String userId, UserRole roleCode) { }
}
