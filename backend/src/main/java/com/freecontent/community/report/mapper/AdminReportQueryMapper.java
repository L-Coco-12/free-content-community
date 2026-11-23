package com.freecontent.community.report.mapper;

import com.freecontent.community.report.dto.ReportRecord.State;
import com.freecontent.community.report.dto.ReportSubmissionRequest.ReasonCode;
import com.freecontent.community.report.dto.ReportSubmissionRequest.TargetType;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminReportQueryMapper {
    String COLUMNS = ReportMapper.RECORD_COLUMNS + ", reporter_id";
    String BY_STATE = " FROM user_report WHERE state=#{state}";

    @Select("SELECT " + COLUMNS + " FROM user_report WHERE id=#{reportId}")
    ReportRow findById(@Param("reportId") String reportId);

    @Select("SELECT COUNT(*)" + BY_STATE)
    long count(@Param("state") String state);

    @Select("<script> SELECT " + COLUMNS + BY_STATE + """

            <choose>
                <when test="state == 'OPEN'">ORDER BY created_at ASC, id ASC</when>
                <otherwise>ORDER BY created_at DESC, id DESC</otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ReportRow> page(@Param("state") String state, @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT report_id, asset_id FROM report_attachment
            WHERE report_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY report_id, position
            </script>
            """)
    List<AttachmentRow> attachments(@Param("ids") List<String> reportIds);

    record ReportRow(String id, TargetType targetType, String targetId, ReasonCode reasonCode, String description,
                     LocalDateTime createdAt, State state, int version, LocalDateTime resolvedAt, String resolution,
                     String reporterId) { }
    record AttachmentRow(String reportId, String assetId) { }
}
