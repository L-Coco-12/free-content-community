package com.freecontent.community.report.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReportAttachmentAccessMapper {
    @Select("""
            SELECT r.reporter_id FROM user_report r
            JOIN report_attachment a ON a.report_id=r.id
            WHERE r.id=#{reportId} AND a.asset_id=#{assetId} FOR SHARE
            """)
    String lockAttachmentReporter(@Param("reportId") String reportId, @Param("assetId") String assetId);

    @Insert("""
            INSERT INTO report_attachment_access_audit(id, actor_id, report_id, asset_id, action, request_id,
                occurred_at, expires_at)
            VALUES (#{id}, #{actorId}, #{reportId}, #{assetId}, 'ADMIN_REPORT_ATTACHMENT_ACCESS', #{requestId},
                #{occurredAt}, #{expiresAt})
            """)
    int insertAudit(AccessAudit audit);

    record AccessAudit(String id, String actorId, String reportId, String assetId, String requestId,
                       LocalDateTime occurredAt, LocalDateTime expiresAt) { }
}
