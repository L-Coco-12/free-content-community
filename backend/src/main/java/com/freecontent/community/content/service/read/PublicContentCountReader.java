package com.freecontent.community.content.service.read;

import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.browse.mapper.PublicContentMapper.AuthorCount;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 内容模块提供公开可见数量；加入调用方快照，复用公开详情和列表的 VISIBLE 条件。 */
@Component
public class PublicContentCountReader {
    private final PublicContentMapper contents;

    public PublicContentCountReader(PublicContentMapper contents) {
        this.contents = contents;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public long countByAuthor(String authorId) {
        return contents.countVisibleByAuthor(authorId);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<String, Long> countByAuthors(List<String> authorIds) {
        if (authorIds.isEmpty()) return Map.of();
        return contents.countVisibleByAuthors(authorIds).stream()
                .collect(Collectors.toMap(AuthorCount::authorId, AuthorCount::visibleCount));
    }
}
