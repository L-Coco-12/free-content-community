package com.freecontent.community.report.mapper;

import com.freecontent.community.report.dto.ReportRecord.State;
import com.freecontent.community.report.dto.ReportSubmissionRequest.ReasonCode;
import com.freecontent.community.report.dto.ReportSubmissionRequest.TargetType;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReportMapper {
    String SUBMISSION_COLUMNS = "id, target_type, COALESCE(content_id, comment_id) AS target_id, reason_code, description, created_at";
    String RECORD_COLUMNS = SUBMISSION_COLUMNS + ", state, version, resolved_at, resolution";
    String BY_REPORTER = " FROM user_report WHERE reporter_id=#{reporter}";
    String BY_REPORTER_AND_ID = BY_REPORTER + " AND id=#{id}";

    @Insert("""
            INSERT INTO user_report(id, reporter_id, target_type, content_id, comment_id, reason_code, description, created_at)
            VALUES (#{r.id}, #{reporter}, #{r.targetType},
                CASE WHEN #{r.targetType}='CONTENT' THEN #{r.targetId} END,
                CASE WHEN #{r.targetType}='COMMENT' THEN #{r.targetId} END,
                #{r.reasonCode}, #{r.description}, #{r.createdAt})
            """)
    int insert(@Param("r") Submission report, @Param("reporter") String reporterId);

    @Insert("""
            INSERT INTO report_attachment(report_id, asset_id, position) VALUES (#{reportId}, #{assetId}, #{position})
            """)
    int attach(@Param("reportId") String reportId, @Param("assetId") String assetId, @Param("position") int position);

    @Select("SELECT " + SUBMISSION_COLUMNS + BY_REPORTER_AND_ID)
    Submission findOwnedSubmission(@Param("id") String id, @Param("reporter") String reporterId);

    @Select("SELECT " + RECORD_COLUMNS + BY_REPORTER_AND_ID)
    ReportRow findOwnedRecord(@Param("id") String id, @Param("reporter") String reporterId);

    @Select("SELECT COUNT(*)" + BY_REPORTER)
    long countOwned(@Param("reporter") String reporterId);

    @Select("SELECT " + RECORD_COLUMNS + BY_REPORTER
            + " ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ReportRow> pageOwned(@Param("reporter") String reporterId, @Param("offset") long offset, @Param("limit") int limit);

    record Submission(String id, TargetType targetType, String targetId, ReasonCode reasonCode, String description,
                      LocalDateTime createdAt) { }

    record ReportRow(String id, TargetType targetType, String targetId, ReasonCode reasonCode, String description,
                     LocalDateTime createdAt, State state, int version, LocalDateTime resolvedAt, String resolution) { }
}
