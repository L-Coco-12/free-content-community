package com.freecontent.community.report.mapper;

import com.freecontent.community.report.dto.ReportDecisionRequest.Decision;
import com.freecontent.community.report.dto.ReportRecord.State;
import com.freecontent.community.report.mapper.AdminReportQueryMapper.ReportRow;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReportDecisionMapper {
    @Select("SELECT " + AdminReportQueryMapper.COLUMNS + " FROM user_report WHERE id=#{id} FOR UPDATE")
    ReportRow lockReport(@Param("id") String reportId);

    @Update("""
            UPDATE user_report SET state=#{state}, version=version+1, resolved_at=#{now}, resolution=#{reason}
            WHERE id=#{id} AND state='OPEN' AND version=#{version}
            """)
    int close(@Param("id") String reportId, @Param("version") int version, @Param("state") State state,
              @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO report_decision_audit(id, report_id, operator_id, decision, reason, content_action_id,
                comment_action_id, before_version, resulting_version, request_id, occurred_at)
            VALUES (#{id}, #{reportId}, #{operatorId}, #{decision}, #{reason}, #{contentActionId,jdbcType=VARCHAR},
                #{commentActionId,jdbcType=VARCHAR}, #{beforeVersion}, #{resultingVersion}, #{requestId}, #{occurredAt})
            """)
    int insertAudit(DecisionAudit audit);

    record DecisionAudit(String id, String reportId, String operatorId, Decision decision, String reason,
                         String contentActionId, String commentActionId, int beforeVersion, int resultingVersion,
                         String requestId, LocalDateTime occurredAt) { }
}
