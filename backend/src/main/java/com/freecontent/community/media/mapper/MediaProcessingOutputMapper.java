package com.freecontent.community.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.media.entity.MediaProcessingOutput;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MediaProcessingOutputMapper extends BaseMapper<MediaProcessingOutput> {
    @Select("SELECT * FROM media_processing_output WHERE id=#{id} FOR UPDATE")
    MediaProcessingOutput lock(String id);

    @Select("SELECT id FROM media_processing_output WHERE (state IN ('RESERVED','CLEANED') AND available_at<=#{now}) OR (state='CLEANING' AND lease_until<=#{now}) ORDER BY available_at,id LIMIT 4")
    java.util.List<String> findDue(java.time.LocalDateTime now);

    @Update("UPDATE media_processing_output SET state=#{state},available_at=#{availableAt},lease_token=#{leaseToken},lease_until=#{leaseUntil} WHERE id=#{id}")
    int saveState(MediaProcessingOutput output);
}
