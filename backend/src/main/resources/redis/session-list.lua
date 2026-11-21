-- 与现有会话键共用{sessions}槽。只读取，不清理或续期，不依赖全库SCAN。
local indexType = redis.call('TYPE', KEYS[1]).ok
if indexType ~= 'none' and indexType ~= 'zset' then return redis.error_reply('SESSION_INDEX_INVALID') end
if indexType == 'none' or redis.call('EXISTS', KEYS[2]) == 0 then return {0} end
local current = redis.call('HMGET', KEYS[2], 'sessionId', 'userId', 'authVersion', 'expiresAt')
if current[1] ~= ARGV[4] or current[2] ~= ARGV[2] or current[3] ~= ARGV[3]
        or not redis.call('ZSCORE', KEYS[1], ARGV[5]) then return {0} end
local expires = tonumber(current[4])
if not expires then return redis.error_reply('SESSION_DATA_INVALID') end
if expires <= tonumber(ARGV[6]) then return {0} end
-- 创建路径的配置硬上限为100，异常膨胀的索引不能造成无界脚本扫描。
if redis.call('ZCARD', KEYS[1]) > 100 then return redis.error_reply('SESSION_INDEX_INVALID') end
local result = {1}
for _, digest in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
    if #digest ~= 64 or not string.match(digest, '^[a-f0-9]+$') then return redis.error_reply('SESSION_INDEX_INVALID') end
    local key = ARGV[1] .. digest
    if redis.call('EXISTS', key) == 1 then
        local values = redis.call('HMGET', key, 'sessionId', 'userId', 'authVersion', 'createdAt', 'expiresAt', 'lastSeenAt', 'deviceLabel')
        if values[2] ~= ARGV[2] then return redis.error_reply('SESSION_INDEX_OWNER_INVALID') end
        if not values[3] or not string.match(values[3], '^[1-9][0-9]*$') then return redis.error_reply('SESSION_VERSION_INVALID') end
        local expiry = tonumber(values[5])
        if not expiry then return redis.error_reply('SESSION_DATA_INVALID') end
        -- BIGINT版本按原始字符串相等比较，避免Lua浮点数丢失精度。
        if values[3] == ARGV[3] and expiry > tonumber(ARGV[6]) then
            for i = 1, 7 do if not values[i] then return redis.error_reply('SESSION_DATA_INVALID') end end
            for _, i in ipairs({1,4,5,6,7}) do table.insert(result, values[i]) end
        end
    end
end
return result
