-- Access only the two declared keys; the shared hash tag makes them one Redis Cluster slot.
local capacity = tonumber(ARGV[1])
local period = tonumber(ARGV[2])
local maximum = tonumber(ARGV[3])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
-- Bounded work even when the namespace has thousands of expired identities.
local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', now, 'LIMIT', 0, 64)
if #expired > 0 then redis.call('ZREM', KEYS[2], unpack(expired)) end
local stored = redis.call('HMGET', KEYS[1], 'tokens', 'updated', 'capacity', 'period')
local tokens = capacity
if stored[1] then
  if tonumber(stored[3]) ~= capacity or tonumber(stored[4]) ~= period then
    return redis.error_reply('Admission policy changed; coordinate configuration rollout')
  end
  tokens = math.min(capacity, tonumber(stored[1]) + math.max(0, now - tonumber(stored[2])) * capacity / period)
end
local indexed = redis.call('ZSCORE', KEYS[2], KEYS[1])
if not indexed and redis.call('ZCARD', KEYS[2]) >= maximum then return {0, 1} end
local allowed = 0
local retry = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry = math.max(1, math.ceil((1 - tokens) * period / capacity / 1000))
end
-- Restriction expires only once the bucket would be completely full again.
local ttl = math.max(1, math.ceil((capacity - tokens) * period / capacity))
-- Reserve cardinality first: a subsequent write failure never creates an uncounted active bucket.
redis.call('ZADD', KEYS[2], now + ttl, KEYS[1])
redis.call('PEXPIRE', KEYS[2], 86401000)
redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', now, 'capacity', capacity, 'period', period)
redis.call('PEXPIRE', KEYS[1], ttl)
return {allowed, retry}
