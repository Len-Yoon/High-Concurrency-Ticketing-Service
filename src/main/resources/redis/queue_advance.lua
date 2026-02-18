-- KEYS[1] = waitingZset
-- KEYS[2] = passZset
-- ARGV[1] = nowMs
-- ARGV[2] = capacity
-- ARGV[3] = passTtlMs
-- ARGV[4] = tokenKeyPrefix   (e.g. "queue:token:12:")
-- ARGV[5] = tokenSeqKey      (e.g. "queue:token:seq:12")

local waitingKey = KEYS[1]
local passKey = KEYS[2]

local nowMs = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local passTtlMs = tonumber(ARGV[3])

local tokenKeyPrefix = ARGV[4]
local tokenSeqKey = ARGV[5]

-- 1) expire된 pass 정리
redis.call('ZREMRANGEBYSCORE', passKey, 0, nowMs)

-- 2) 남은 자리 계산
local passCount = tonumber(redis.call('ZCARD', passKey))
local deficit = capacity - passCount
if deficit <= 0 then
  return {0}
end

local advanced = 0

for i=1, deficit do
  local popped = redis.call('ZPOPMIN', waitingKey, 1)
  if (popped == nil) or (#popped == 0) then
    break
  end

  local userId = popped[1]
  local expireAt = nowMs + passTtlMs

  -- 3) pass 등록 (만료 시각을 score로)
  redis.call('ZADD', passKey, expireAt, userId)

  -- 4) token 발급 + TTL 세팅
  local seq = redis.call('INCR', tokenSeqKey)
  local token = tostring(nowMs) .. ":" .. tostring(seq) .. ":" .. userId
  local tokenKey = tokenKeyPrefix .. userId
  redis.call('SET', tokenKey, token, 'PX', passTtlMs)

  advanced = advanced + 1
end

return {advanced}
