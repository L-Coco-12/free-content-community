package com.freecontent.community.content.service.read;

import com.freecontent.community.content.mapper.ContentBookmarkReadMapper;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@Transactional(propagation=Propagation.MANDATORY,readOnly=true)
public class ContentBookmarkReader {
    private final ContentBookmarkReadMapper bookmarks;
    public ContentBookmarkReader(ContentBookmarkReadMapper bookmarks){this.bookmarks=bookmarks;}
    public Set<String> readAll(List<String> ids,String viewer){
        return viewer==null || ids.isEmpty()?Set.of():Set.copyOf(bookmarks.find(ids,viewer));
    }
}
