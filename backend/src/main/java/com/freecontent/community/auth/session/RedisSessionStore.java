package com.freecontent.community.auth.session;

import com.freecontent.community.auth.config.LoginProperties;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;

@Component
public class RedisSessionStore {
    private final StringRedisTemplate redis;
    private final LoginProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final DefaultRedisScript<Long> create;
    private final DefaultRedisScript<List> read;
    private final DefaultRedisScript<Long> revoke;
    private final DefaultRedisScript<Long> revokeBeforeVersion;
    private final DefaultRedisScript<List> list;
    private final DefaultRedisScript<Long> revokeOwned;

    public RedisSessionStore(StringRedisTemplate redis, LoginProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        create = new DefaultRedisScript<>();
        create.setLocation(new ClassPathResource("redis/session-create.lua"));
        create.setResultType(Long.class);
        read = new DefaultRedisScript<>();
        read.setLocation(new ClassPathResource("redis/session-read.lua"));
        read.setResultType(List.class);
        revoke = new DefaultRedisScript<>();
        revoke.setLocation(new ClassPathResource("redis/session-revoke.lua"));
        revoke.setResultType(Long.class);
        revokeBeforeVersion = new DefaultRedisScript<>();
        revokeBeforeVersion.setLocation(new ClassPathResource("redis/session-revoke-before-version.lua"));
        revokeBeforeVersion.setResultType(Long.class);
        list = new DefaultRedisScript<>();
        list.setLocation(new ClassPathResource("redis/session-list.lua"));
        list.setResultType(List.class);
        revokeOwned = new DefaultRedisScript<>();
        revokeOwned.setLocation(new ClassPathResource("redis/session-revoke-owned.lua"));
        revokeOwned.setResultType(Long.class);
    }

    public IssuedSession create(String userId, long authVersion, String deviceLabel) {
        Instant now = Instant.ofEpochMilli(clock.millis());
        StoredSession session = new StoredSession(UUID.randomUUID().toString().replace("-", ""),
                userId, authVersion, now, now.plusSeconds(properties.ttlSeconds()), deviceLabel);
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String digest = TokenDigest.sha256(token);
            try {
                Long result = redis.execute(create,
                        List.of(prefix() + "user:" + userId, prefix() + "token:" + digest),
                        prefix() + "token:", digest, session.sessionId(), userId, Long.toString(authVersion),
                        Long.toString(now.toEpochMilli()), Long.toString(session.expiresAt().toEpochMilli()),
                        deviceLabel == null ? "" : deviceLabel, Integer.toString(properties.maxSessions()));
                if (result == null) throw unavailable();
                if (result == 1) return new IssuedSession(token, session);
                if (result == -1) throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "邮箱或密码不正确");
                if (result != 0) throw unavailable();
            } catch (DataAccessException ex) {
                // 写入结果未知时不签发Token，不将依赖故障伪装为凭据错误。
                throw unavailable();
            }
        }
        throw unavailable();
    }

    public StoredSession find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            String digest = TokenDigest.sha256(token);
            List<?> values = redis.execute(read, List.of(prefix() + "token:" + digest),
                    Long.toString(clock.millis()), prefix() + "user:", digest);
            if (values == null) throw unavailable();
            if (values.isEmpty()) return null;
            if (values.size() != 6) throw unavailable();
            return new StoredSession((String) values.get(0), (String) values.get(1),
                    Long.parseLong((String) values.get(2)),
                    Instant.ofEpochMilli(Long.parseLong((String) values.get(3))),
                    Instant.ofEpochMilli(Long.parseLong((String) values.get(4))), (String) values.get(5));
        } catch (DataAccessException | IllegalArgumentException ex) {
            throw unavailable();
        }
    }

    public void revoke(String userId, String sessionId, String tokenDigest) {
        if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,64}")
                || sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,64}")
                || tokenDigest == null || !tokenDigest.matches("[a-f0-9]{64}")) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        try {
            Long result = redis.execute(revoke,
                    List.of(prefix() + "token:" + tokenDigest, prefix() + "user:" + userId),
                    userId, sessionId, tokenDigest);
            if (result == null) throw unavailable();
            if (result == -1) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
            // 已通过认证、但并发退出或过期使记录先消失时，目标同样已被撤销。
            if (result != 0 && result != 1) throw unavailable();
        } catch (DataAccessException ex) {
            throw unavailable();
        }
    }

    public long revokeBeforeVersion(String userId, long authVersion) {
        if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,64}") || authVersion < 1) throw unavailable();
        try {
            Long result = redis.execute(revokeBeforeVersion, List.of(prefix() + "user:" + userId),
                    prefix() + "token:", userId, Long.toString(authVersion));
            if (result == null || result < 0) throw unavailable();
            return result;
        } catch (DataAccessException ex) {
            throw unavailable();
        }
    }

    public List<SessionMetadata> listActive(String userId,long authVersion,String currentId,String currentDigest) {
        requireCurrentIdentity(userId,authVersion,currentId,currentDigest);
        try {
            List<?> values=redis.execute(list,List.of(prefix()+"user:"+userId,prefix()+"token:"+currentDigest),
                    prefix()+"token:",userId,Long.toString(authVersion),currentId,currentDigest,Long.toString(clock.millis()));
            return metadata(values,currentId);
        }catch(DataAccessException | IllegalArgumentException ex){throw unavailable();}
    }

    public void revokeOwned(String userId,long authVersion,String currentId,String currentDigest,String targetId) {
        requireCurrentIdentity(userId,authVersion,currentId,currentDigest);
        if(targetId==null || !targetId.matches("[A-Za-z0-9_-]{1,64}"))throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        try {
            Long result=redis.execute(revokeOwned,List.of(prefix()+"user:"+userId,prefix()+"token:"+currentDigest),
                    prefix()+"token:",userId,Long.toString(authVersion),currentId,currentDigest,targetId,Long.toString(clock.millis()));
            if(result==null)throw unavailable();
            if(result==-1)throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
            if(result!=0 && result!=1)throw unavailable();
        }catch(DataAccessException ex){throw unavailable();}
    }

    private void requireCurrentIdentity(String userId,long authVersion,String currentId,String currentDigest) {
        if(userId==null || !userId.matches("[A-Za-z0-9_-]{1,64}") || currentId==null || !currentId.matches("[A-Za-z0-9_-]{1,64}")
                || currentDigest==null || !currentDigest.matches("[a-f0-9]{64}") || authVersion<1)throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    private List<SessionMetadata> metadata(List<?> values,String currentId) {
        if(values==null || values.isEmpty() || !(values.get(0) instanceof Number status)
                || status.doubleValue()!=status.longValue())throw unavailable();
        if(status.longValue()==0 && values.size()==1)throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if(status.longValue()!=1 || (values.size()-1)%5!=0 || values.size()>501)throw unavailable();
        var rows=new ArrayList<SessionMetadata>();
        var ids=new HashSet<String>();
        for(int offset=1;offset<values.size();offset+=5){
            for(int i=0;i<5;i++)if(!(values.get(offset+i) instanceof String))throw unavailable();
            String id=(String)values.get(offset),label=(String)values.get(offset+4);
            long created=timestamp((String)values.get(offset+1));
            long expires=timestamp((String)values.get(offset+2));
            long seen=timestamp((String)values.get(offset+3));
            if(!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id) || expires<=created || seen<created || seen>=expires
                    || label.codePointCount(0,label.length())>120)throw unavailable();
            rows.add(new SessionMetadata(id,Instant.ofEpochMilli(created),Instant.ofEpochMilli(expires),Instant.ofEpochMilli(seen),label.strip()));
        }
        if(!ids.contains(currentId))throw unavailable();
        return List.copyOf(rows);
    }

    private long timestamp(String value){
        if(!value.matches("0|[1-9][0-9]*"))throw unavailable();
        return Long.parseLong(value);
    }

    private String prefix() { return properties.redisKeyPrefix() + "{sessions}:"; }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "登录会话服务暂不可用");
    }
}
