package com.freecontent.community.user.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.service.read.PublicContentCountReader;
import com.freecontent.community.user.cursor.MyFollowingCursor;
import com.freecontent.community.user.dto.PublicUserView;
import com.freecontent.community.user.dto.UserPage;
import com.freecontent.community.user.mapper.UserFollowMapper;
import com.freecontent.community.user.mapper.UserFollowMapper.FollowedRow;
import com.freecontent.community.user.model.PublicUserProfile;
import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.service.read.PublicAvatarUrlFactory;
import com.freecontent.community.user.service.read.PublicUserReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyFollowingTransactionService {
    private final AccountAccessService accounts;
    private final UserFollowMapper follows;
    private final PublicUserReader profiles;
    private final PublicContentCountReader contents;
    private final PublicAvatarUrlFactory avatars;
    private final MyFollowingCursor cursors;

    public MyFollowingTransactionService(AccountAccessService accounts, UserFollowMapper follows,
                                         PublicUserReader profiles, PublicContentCountReader contents,
                                         PublicAvatarUrlFactory avatars, MyFollowingCursor cursors) {
        this.accounts = accounts;
        this.follows = follows;
        this.profiles = profiles;
        this.contents = contents;
        this.avatars = avatars;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserPage list(MyFollowingCursor.Query query, SessionPrincipal actor) {
        String viewerId = authorize(actor);
        var rows = follows.list(viewerId, utc(query.snapshotAt()), utc(query.lastAt()), query.lastId(), query.limit() + 1);
        boolean hasMore = rows.size() > query.limit();
        var pageRows = rows.subList(0, Math.min(rows.size(), query.limit()));
        var ids = pageRows.stream().map(FollowedRow::followedId).toList();
        var byId = profiles.readAll(ids).stream().collect(Collectors.toMap(PublicUserProfile::id, Function.identity()));
        var counts = contents.countByAuthors(ids);

        List<PublicUserView> items = new ArrayList<>(pageRows.size());
        for (String id : ids) {
            var profile = byId.get(id);
            // 用户外键与事务快照保证关系与资料应同时可读。
            if (profile == null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "关注用户资料暂不可用");
            }
            items.add(new PublicUserView(id, profile.displayName(), profile.bio(), avatars.url(id, profile.avatarAssetId()),
                    profile.createdAt().toInstant(ZoneOffset.UTC), counts.getOrDefault(id, 0L), profile.followerCount()));
        }

        String nextCursor = null;
        if (hasMore) {
            var last = pageRows.get(pageRows.size() - 1);
            nextCursor = cursors.next(query, last.createdAt().toInstant(ZoneOffset.UTC), last.id());
        }
        return new UserPage(items, nextCursor, hasMore, query.snapshotAt());
    }

    private String authorize(SessionPrincipal actor) {
        var account = accounts.findByIdForShare(actor.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != actor.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!account.emailVerified()) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return account.id();
    }

    private static LocalDateTime utc(Instant time) {
        return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }
}
