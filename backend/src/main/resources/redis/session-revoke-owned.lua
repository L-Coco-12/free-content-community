-- 本人索引查找及删除在同一个{sessions}槽原子执行；不扫描全库，不接受客户端凭证摘要。
local indexType = redis.call('TYPE', KEYS[1]).ok
if indexType ~= 'none' and indexType ~= 'zset' then return redis.error_reply('SESSION_INDEX_INVALID') end
local self = ARGV[6] == ARGV[4]
if redis.call('EXISTS', KEYS[2]) == 0 then
    if not self then return -1 end
    -- 已认证的当前会话已被并发退出或过期，仍可幂等完成其撤销。
    redis.call('ZREM', KEYS[1], ARGV[5])
    return 0
end
local current = redis.call('HMGET', KEYS[2], 'sessionId', 'userId', 'authVersion', 'expiresAt')
if current[1] ~= ARGV[4] or current[2] ~= ARGV[2] or current[3] ~= ARGV[3] then return -1 end
if self then
    redis.call('DEL', KEYS[2])
    redis.call('ZREM', KEYS[1], ARGV[5])
    return 1
end
if not redis.call('ZSCORE', KEYS[1], ARGV[5]) then return -1 end
if not current[4] or not string.match(current[4], '^[0-9]+$') then return redis.error_reply('SESSION_DATA_INVALID') end
local expires = tonumber(current[4])
if not expires or expires == math.huge then return redis.error_reply('SESSION_DATA_INVALID') end
if expires <= tonumber(ARGV[7]) then return -1 end
if redis.call('ZCARD', KEYS[1]) > 100 then return redis.error_reply('SESSION_INDEX_INVALID') end
local target = nil
local ids = {}
-- Lua出错不自动回滚。先校验所有候选身份，再执行一次目标删除，避免半途出错造成部分撤销。
for _, digest in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
    if #digest ~= 64 or not string.match(digest, '^[a-f0-9]+$') then return redis.error_reply('SESSION_INDEX_INVALID') end
    local key = ARGV[1] .. digest
    if redis.call('EXISTS', key) == 1 then
        local identity = redis.call('HMGET', key, 'userId', 'sessionId')
        if identity[1] ~= ARGV[2] then return redis.error_reply('SESSION_INDEX_OWNER_INVALID') end
        if not identity[2] or #identity[2] > 64 or not string.match(identity[2], '^[A-Za-z0-9_-]+$') then
            return redis.error_reply('SESSION_DATA_INVALID')
        end
        if ids[identity[2]] then return redis.error_reply('SESSION_INDEX_DUPLICATE') end
        ids[identity[2]] = true
        if identity[2] == ARGV[6] then target = digest end
    end
end
if not target then return 0 end
redis.call('DEL', ARGV[1] .. target)
redis.call('ZREM', KEYS[1], target)
return 1
