package com.freecontent.community.content.comment.mapper;

import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CommentMapper {
    String PUBLIC_COLUMNS = """
            cm.id, cm.content_id, cm.root_comment_id, cm.reply_to_comment_id,
            CASE WHEN cm.state='VISIBLE' THEN cm.author_id END AS author_id,
            CASE WHEN cm.state='VISIBLE' THEN cm.body END AS body,
            cm.state, cm.created_at
            """;
    String VISIBLE_REPLIES = """
            FROM content_comment reply
            WHERE reply.content_id=cm.content_id AND reply.root_comment_id=cm.id AND reply.state='VISIBLE'
            """;
    String READABLE_ROOT = "cm.root_comment_id IS NULL AND (cm.state='VISIBLE' OR EXISTS (SELECT 1 "
            + VISIBLE_REPLIES + "))";
    String PUBLIC_CONTENT = "EXISTS (SELECT 1 " + PublicContentMapper.PUBLISHED_JOIN + " WHERE c.id=cm.content_id AND "
            + PublicContentMapper.VISIBLE + ")";

    @Select("""
            SELECT id, author_id, root_comment_id, state
            FROM content_comment WHERE content_id=#{contentId} AND id=#{id} FOR SHARE
            """)
    CommentTarget lockTarget(@Param("contentId") String contentId, @Param("id") String id);

    @Select("SELECT content_id FROM content_comment WHERE id=#{id} AND state='VISIBLE'")
    String findVisibleContentId(@Param("id") String id);

    @Update("""
            UPDATE content_comment SET state='DELETED', version=version+1, updated_at=#{now}
            WHERE id=#{id} AND author_id=#{author} AND state IN ('VISIBLE', 'HIDDEN')
            """)
    int markOwnedDeleted(@Param("id") String id, @Param("author") String authorId, @Param("now") LocalDateTime now);

    @Select("SELECT state FROM content_comment WHERE id=#{id} AND author_id=#{author} FOR SHARE")
    String findOwnedState(@Param("id") String id, @Param("author") String authorId);

    @Select("<script> SELECT " + PUBLIC_COLUMNS + ", CASE WHEN cm.root_comment_id IS NULL THEN (SELECT COUNT(*) "
            + VISIBLE_REPLIES + ") ELSE 0 END AS reply_count FROM content_comment cm "
            + "WHERE cm.id=#{id} AND " + PUBLIC_CONTENT + " </script>")
    PublicCommentRow findPublic(@Param("id") String id);

    @Select("<script> SELECT cm.content_id FROM content_comment cm WHERE cm.id=#{id} AND "
            + READABLE_ROOT + " AND " + PUBLIC_CONTENT + " </script>")
    String findPublicThreadContentId(@Param("id") String rootCommentId);

    @Select("<script> SELECT " + PUBLIC_COLUMNS + ", (SELECT COUNT(*) " + VISIBLE_REPLIES + ") AS reply_count " + """
            FROM content_comment cm
            WHERE cm.content_id=#{content} AND cm.created_at &lt;= #{snapshot} AND
            """ + READABLE_ROOT + """

            <if test="lastAt != null">
                AND (cm.created_at &lt; #{lastAt} OR (cm.created_at=#{lastAt} AND cm.id &lt; #{lastId}))
            </if>
            ORDER BY cm.created_at DESC, cm.id DESC
            LIMIT #{take}
            </script>
            """)
    List<PublicCommentRow> listPublicRoots(@Param("content") String contentId,
                                           @Param("snapshot") LocalDateTime snapshot,
                                           @Param("lastAt") LocalDateTime lastAt,
                                           @Param("lastId") String lastId, @Param("take") int take);

    @Select("<script> SELECT " + PUBLIC_COLUMNS + ", 0 AS reply_count " + """
            FROM content_comment cm
            WHERE cm.content_id=#{content} AND cm.root_comment_id=#{root} AND cm.state='VISIBLE'
              AND cm.created_at &lt;= #{snapshot}
            <if test="lastAt != null">
                AND (cm.created_at &gt; #{lastAt} OR (cm.created_at=#{lastAt} AND cm.id &gt; #{lastId}))
            </if>
            ORDER BY cm.created_at ASC, cm.id ASC
            LIMIT #{take}
            </script>
            """)
    List<PublicCommentRow> listPublicReplies(@Param("content") String contentId, @Param("root") String rootCommentId,
                                            @Param("snapshot") LocalDateTime snapshot, @Param("lastAt") LocalDateTime lastAt,
                                            @Param("lastId") String lastId, @Param("take") int take);

    @Insert("""
            INSERT INTO content_comment(id, content_id, author_id, root_comment_id, reply_to_comment_id,
                                        body, state, created_at, updated_at)
            VALUES (#{c.id}, #{c.contentId}, #{c.authorId}, #{c.rootCommentId}, #{c.replyToCommentId},
                    #{c.body}, 'VISIBLE', #{c.createdAt}, #{c.createdAt})
            """)
    int insert(@Param("c") CommentInsert comment);

    record CommentTarget(String id, String authorId, String rootCommentId, String state) { }
    record CommentInsert(String id, String contentId, String authorId, String rootCommentId,
                         String replyToCommentId, String body, LocalDateTime createdAt) { }
    record PublicCommentRow(String id, String contentId, String rootCommentId, String replyToCommentId,
                            String authorId, String body, String state, LocalDateTime createdAt, long replyCount) { }
}
