package com.freecontent.community.content.service.read;

import com.freecontent.community.content.mapper.ContentLikeReadMapper;
import com.freecontent.community.content.model.persistence.ContentLikeState;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** 在公开详情/列表的同一快照内批量读取统计和当前访问者关系。 */
@Component
@Transactional(propagation=Propagation.MANDATORY,readOnly=true)
public class ContentLikeReader {
    private final ContentLikeReadMapper likes;
    public ContentLikeReader(ContentLikeReadMapper likes){this.likes=likes;}
    public Map<String,ContentLikeState> readAll(List<String> ids,String viewerId){
        return ids.isEmpty()?Map.of():likes.find(ids,viewerId).stream().collect(Collectors.toMap(ContentLikeState::contentId,Function.identity()));
    }
}
