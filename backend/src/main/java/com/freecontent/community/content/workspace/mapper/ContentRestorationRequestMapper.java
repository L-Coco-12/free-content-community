package com.freecontent.community.content.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.content.workspace.entity.ContentRestorationRequestRecord;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContentRestorationRequestMapper extends BaseMapper<ContentRestorationRequestRecord> {
    @Select("SELECT * FROM content_restoration_request WHERE created_by=#{actor} AND key_hash=#{key} FOR UPDATE")
    ContentRestorationRequestRecord lockByKey(@Param("actor") String actor, @Param("key") String key);

    @Update("UPDATE content_restoration_request SET lease_token=#{token},lease_until=#{until} WHERE id=#{id} AND state='PROCESSING'")
    int renew(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);

    @Update("""
        UPDATE content_restoration_request SET state='SUCCEEDED',content_id=#{content},
            result_snapshot=#{snapshot},lease_token=NULL,lease_until=NULL
        WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token} AND lease_until>#{now} AND expires_at>#{now}
        """)
    int complete(@Param("id") String id, @Param("token") String token, @Param("content") String content,
            @Param("snapshot") String snapshot, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM content_restoration_request WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token}")
    int release(@Param("id") String id, @Param("token") String token);
}
