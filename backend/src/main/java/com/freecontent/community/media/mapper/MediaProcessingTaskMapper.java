package com.freecontent.community.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.media.entity.MediaProcessingTask;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MediaProcessingTaskMapper extends BaseMapper<MediaProcessingTask> {
    @Select("SELECT id FROM media_processing_task WHERE type='MEDIA_PROCESSING' AND ((state='PENDING' AND (available_at IS NULL OR available_at<=#{now})) OR (state='RUNNING' AND (lease_until IS NULL OR lease_until<=#{now}))) ORDER BY created_at,id LIMIT 1")
    java.util.List<String> findDue(java.time.LocalDateTime now);

    @Select("SELECT * FROM media_processing_task WHERE id=#{id} FOR UPDATE")
    MediaProcessingTask lock(String id);

    @Update("UPDATE media_processing_task SET state=#{state},progress_percent=#{progressPercent},retryable=#{retryable},error_code=#{errorCode},error_message=#{errorMessage},attempt_count=#{attemptCount},lease_token=#{leaseToken},lease_until=#{leaseUntil},available_at=#{availableAt},updated_at=#{updatedAt} WHERE id=#{id}")
    int saveState(MediaProcessingTask task);
    @Select("SELECT * FROM media_processing_task WHERE id=#{id} AND owner_id=#{owner} AND type='MEDIA_PROCESSING'")
    MediaProcessingTask findOwned(@Param("id") String id, @Param("owner") String owner);
}
