package com.freecontent.community.content.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.content.workspace.entity.ContentDraftCreationRequestRecord;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContentDraftCreationRequestMapper extends BaseMapper<ContentDraftCreationRequestRecord> {
    @Select("SELECT * FROM content_draft_creation_request WHERE created_by=#{actor} AND key_hash=#{key} FOR UPDATE")
    ContentDraftCreationRequestRecord lockByKey(@Param("actor") String actor, @Param("key") String key);

    @Update("UPDATE content_draft_creation_request SET lease_token=#{token},lease_until=#{until} WHERE id=#{id} AND state='PROCESSING'")
    int renew(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);

    @Update("""
        UPDATE content_draft_creation_request SET state='SUCCEEDED',content_id=#{content},revision_id=#{revision},
            result_snapshot=#{snapshot},lease_token=NULL,lease_until=NULL
        WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token} AND lease_until>#{now} AND expires_at>#{now}
        """)
    int complete(@Param("id") String id, @Param("token") String token, @Param("content") String content,
            @Param("revision") String revision, @Param("snapshot") String snapshot, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM content_draft_creation_request WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token}")
    int release(@Param("id") String id, @Param("token") String token);
}
