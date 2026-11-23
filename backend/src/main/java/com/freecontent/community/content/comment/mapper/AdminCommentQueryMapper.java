package com.freecontent.community.content.comment.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminCommentQueryMapper {
    String COLUMNS = "id, content_id, author_id, body, state, version, created_at";
    String FILTER = """
            FROM content_comment
            <where>
                <if test="content != null">AND content_id=#{content}</if>
                <if test="author != null">AND author_id=#{author}</if>
                <if test="state != null">AND state=#{state}</if>
            </where>
            """;

    @Select("<script> SELECT COUNT(*) " + FILTER + " </script>")
    long count(@Param("content") String contentId, @Param("author") String authorId, @Param("state") String state);

    @Select("SELECT " + COLUMNS + " FROM content_comment WHERE id=#{id}")
    CommentRow findById(@Param("id") String commentId);

    @Select("<script> SELECT " + COLUMNS + " " + FILTER
            + " ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset} </script>")
    List<CommentRow> page(@Param("content") String contentId, @Param("author") String authorId,
                          @Param("state") String state, @Param("offset") long offset, @Param("limit") int limit);

    record CommentRow(String id, String contentId, String authorId, String body, String state, int version,
                      LocalDateTime createdAt) { }
}
