package com.freecontent.community.content.comment.service.read;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.service.read.PublicContentCountReader;
import com.freecontent.community.user.dto.PublicUserView;
import com.freecontent.community.user.model.PublicUserProfile;
import com.freecontent.community.user.service.read.PublicAvatarUrlFactory;
import com.freecontent.community.user.service.read.PublicUserReader;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CommentAuthorReader {
    private final PublicUserReader users;
    private final PublicContentCountReader counts;
    private final PublicAvatarUrlFactory avatars;

    public CommentAuthorReader(PublicUserReader users, PublicContentCountReader counts, PublicAvatarUrlFactory avatars) {
        this.users = users;
        this.counts = counts;
        this.avatars = avatars;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public PublicUserView read(String authorId) {
        var user = users.read(authorId);
        if (user == null) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "评论作者公开资料暂不可用");
        return view(user, counts.countByAuthor(user.id()));
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<String, PublicUserView> readAll(List<String> authorIds) {
        if (authorIds.isEmpty()) return Map.of();
        var profiles = users.readAll(authorIds);
        var published = counts.countByAuthors(authorIds);
        return profiles.stream().collect(Collectors.toMap(PublicUserProfile::id,
                user -> view(user, published.getOrDefault(user.id(), 0L))));
    }

    private PublicUserView view(PublicUserProfile user, long publishedContentCount) {
        return new PublicUserView(user.id(), user.displayName(), user.bio(), avatars.url(user.id(), user.avatarAssetId()),
                user.createdAt().toInstant(ZoneOffset.UTC), publishedContentCount, user.followerCount());
    }
}
