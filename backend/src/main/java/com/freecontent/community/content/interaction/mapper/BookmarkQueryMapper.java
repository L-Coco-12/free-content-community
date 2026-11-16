package com.freecontent.community.content.interaction.mapper;

import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.browse.mapper.PublicContentMapper.PublishedRow;
import com.freecontent.community.content.model.ContentKind;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BookmarkQueryMapper {
    record BookmarkedRow(String bookmarkId,LocalDateTime bookmarkedAt,String id,String authorId,ContentKind kind,
            String lifecycle,String publicationStatus,String publishedRevisionId,LocalDateTime firstPublishedAt,
            LocalDateTime publishedAt,String revisionId,String revisionStatus,String title,String bodyMarkdown) {
        public PublishedRow published(){return new PublishedRow(id,authorId,kind,lifecycle,publicationStatus,publishedRevisionId,
                firstPublishedAt,publishedAt,revisionId,revisionStatus,title,bodyMarkdown);}
    }
    @Select("<script> SELECT b.id AS bookmark_id,b.created_at AS bookmarked_at,"+PublicContentMapper.COLUMNS
            +PublicContentMapper.PUBLISHED_JOIN+" JOIN content_bookmark b ON b.content_id=c.id WHERE b.user_id=#{user} AND "
            +PublicContentMapper.VISIBLE+"""
            AND b.created_at &lt;= #{snapshot}
            <if test="lastAt != null">
              AND (b.created_at &lt; #{lastAt} OR (b.created_at=#{lastAt} AND b.id &lt; #{lastId}))
            </if>
            ORDER BY b.created_at DESC,b.id DESC LIMIT #{take} </script>
            """)
    List<BookmarkedRow> list(@Param("user") String user,@Param("snapshot") LocalDateTime snapshot,
            @Param("lastAt") LocalDateTime lastAt,@Param("lastId") String lastId,@Param("take") int take);
}
