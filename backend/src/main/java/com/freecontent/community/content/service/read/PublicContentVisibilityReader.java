package com.freecontent.community.content.service.read;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.browse.mapper.PublishedMediaAccessMapper;
import com.freecontent.community.content.browse.service.read.PublicContentAssembler;
import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.content.model.persistence.RevisionRow;
import com.freecontent.community.media.entity.MediaAsset;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 已授权写事务内复核候选发布稿，复用公开投影规则，并将素材锁持有至提交。 */
@Component
public class PublicContentVisibilityReader {
    private final PublishedMediaAccessMapper references;
    private final PublicContentAssembler assembler;
    public PublicContentVisibilityReader(PublishedMediaAccessMapper references, PublicContentAssembler assembler) {
        this.references = references; this.assembler = assembler;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireVisible(ContentRow content, RevisionRow revision, LocalDateTime firstPublishedAt, LocalDateTime publishedAt) {
        var candidate = new PublicContentMapper.PublishedRow(content.id(), content.authorId(), content.kind(), content.lifecycle(),
                "PUBLISHED", revision.id(), firstPublishedAt, publishedAt, revision.id(), revision.status(), revision.title(), revision.bodyMarkdown());
        try {
            assembler.requirePublicRevision(candidate);
            requireReferences(candidate);
        } catch (BusinessException failure) {
            if (failure.errorCode() == ErrorCode.RESOURCE_NOT_FOUND) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE, "批准稿当前不满足公开条件");
            throw failure;
        }
    }

    /** 当前公开作品的互动入口；使用真实发布状态，不能把撤回稿当成候选发布稿。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireCurrentlyVisible(String contentId) {
        var content=references.lockPublication(contentId);
        if(content==null || !"ACTIVE".equals(content.lifecycle()) || !"PUBLISHED".equals(content.publicationStatus()))
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var revision=content.publishedRevisionId()==null?null:references.lockRevision(contentId,content.publishedRevisionId());
        var current=new PublicContentMapper.PublishedRow(content.id(),content.authorId(),content.kind(),content.lifecycle(),
                content.publicationStatus(),content.publishedRevisionId(),content.firstPublishedAt(),content.publishedAt(),
                revision==null?null:revision.id(),revision==null?null:revision.status(),revision==null?null:revision.title(),revision==null?null:revision.bodyMarkdown());
        assembler.requirePublicRevision(current);
        requireReferences(current);
    }

    private void requireReferences(PublicContentMapper.PublishedRow candidate) {
        var bindings=references.lockReferences(candidate.revisionId());
        var ids=bindings.stream().map(value->value.assetId()).distinct().sorted().toList();
        Map<String,MediaAsset> assets=ids.isEmpty()?Map.of():references.lockAssets(candidate.authorId(),ids).stream()
                .collect(Collectors.toMap(MediaAsset::getId,Function.identity()));
        var rows=bindings.stream().map(binding->{
            var asset=assets.get(binding.assetId());
            return new PublicContentMapper.AssetRow(binding.assetId(),binding.usageType(),binding.position(),
                    asset==null?null:asset.getOwnerId(),asset==null?null:asset.getScene(),asset==null?null:asset.getState());
        }).toList();
        assembler.media(candidate,rows);
        assembler.tags(references.lockTags(candidate.revisionId()));
    }
}
