package com.freecontent.community.content.service.read;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.dto.PublicContentSummaryView;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.browse.mapper.PublicContentMapper.*;
import com.freecontent.community.content.browse.service.read.PublicContentAssembler;
import com.freecontent.community.content.model.persistence.ContentLikeState;
import com.freecontent.community.user.model.PublicUserProfile;
import com.freecontent.community.user.service.read.PublicUserReader;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 在调用方的快照中批量构造公开卡片；数据库故障不得被按条过滤吞掉。 */
@Service
public class PublicContentSummaryReader {
    private final PublicContentMapper contents;
    private final PublicUserReader users;
    private final PublicContentAssembler assembler;
    private final ContentLikeReader likes;
    private final ContentBookmarkReader bookmarks;
    public PublicContentSummaryReader(PublicContentMapper contents,PublicUserReader users,PublicContentAssembler assembler,ContentLikeReader likes,ContentBookmarkReader bookmarks){
        this.contents=contents;this.users=users;this.assembler=assembler;this.likes=likes;this.bookmarks=bookmarks;
    }
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public List<PublicContentSummaryView> readAll(List<PublishedRow> rows,String viewerId){return readAll(rows,viewerId,null);}
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public List<PublicContentSummaryView> readAll(List<PublishedRow> rows,String viewerId,Map<String,PublicUserProfile> knownAuthors){
        if(rows.isEmpty())return List.of();
        var revisions=rows.stream().map(PublishedRow::revisionId).toList();
        var authors=rows.stream().map(PublishedRow::authorId).distinct().toList();
        Map<String,List<AssetRow>> assets=new HashMap<>();
        for(var value:contents.findAssetsForRevisions(revisions))assets.computeIfAbsent(value.revisionId(),ignored->new ArrayList<>())
                .add(new AssetRow(value.assetId(),value.usageType(),value.position(),value.ownerId(),value.scene(),value.state()));
        Map<String,List<TagRow>> tags=new HashMap<>();
        for(var value:contents.findTagsForRevisions(revisions))tags.computeIfAbsent(value.revisionId(),ignored->new ArrayList<>())
                .add(new TagRow(value.id(),value.name(),value.description(),value.status(),value.version()));
        var profiles=knownAuthors==null?users.readAll(authors).stream().collect(Collectors.toMap(PublicUserProfile::id,Function.identity())):knownAuthors;
        var counts=contents.countVisibleByAuthors(authors).stream().collect(Collectors.toMap(AuthorCount::authorId,AuthorCount::visibleCount));
        var ids=rows.stream().map(PublishedRow::id).toList();
        var likeStates=likes.readAll(ids,viewerId);
        var bookmarkedIds=bookmarks.readAll(ids,viewerId);
        List<PublicContentSummaryView> items=new ArrayList<>();
        for(var row:rows){
            try{
                assembler.requirePublicRevision(row);
                var media=assembler.media(row,assets.getOrDefault(row.revisionId(),List.of()));
                var labels=assembler.tags(tags.getOrDefault(row.revisionId(),List.of()));
                items.add(PublicContentSummaryView.from(assembler.content(row,media,labels,profiles.get(row.authorId()),counts.getOrDefault(row.authorId(),0L),
                        likeStates.getOrDefault(row.id(),ContentLikeState.none(row.id())),bookmarkedIds.contains(row.id()))));
            }catch(BusinessException ex){
                if(ex.errorCode()!=ErrorCode.RESOURCE_NOT_FOUND && ex.errorCode()!=ErrorCode.DEPENDENCY_UNAVAILABLE)throw ex;
            }
        }
        return List.copyOf(items);
    }
}
