package com.freecontent.community.content.model.persistence;

public record ContentLikeState(String contentId,long likeCount,boolean viewerHasLiked) {
    public static ContentLikeState none(String id){return new ContentLikeState(id,0,false);}
}
