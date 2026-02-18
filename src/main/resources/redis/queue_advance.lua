-- KEYS[1] = waitingZsetKey          (queue:{scheduleId})
-- KEYS[2] = passZsetKey             (queue:pass:z:{scheduleId})
-- ARGV[1] = nowMs
-- ARGV[2] = capacity
-- ARGV[3] = passTtlMs
-- ARGV[4] = tokenKeyPrefix          (queue:pass:{scheduleId}:)
-- ARGV[5] = tokenSeqKey             (queue:pass:seq:{scheduleId})

local waitingKey = KEYS[1]
local passZKey = KEYS[2]

local nowMs = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local passTtlMs = tonumber(ARGV[3])

local tokenKeyPrefix = ARGV[4]
local tokenSeqKey = ARGV[5]

-- 1) 만료된 pass 정리 (score=expireAtMs)
redis.call('ZREMRANGEBYSCORE', passZKey, 0, nowMs)

-- 2) 남은 자리 계산
local passCount = tonumber(redis.call('ZCARD', passZKey))
local deficit = capacity - passCount
if deficit <= 0 then
  return 0
end

local advanced = 0

for i=1, deficit do
  local popped = redis.call('ZPOPMIN', waitingKey, 1)
  if (popped == nil) or (#popped == 0) then
    break
  end

  local userId = popped[1]
  local expireAt = nowMs + passTtlMs

  -- 3) pass 인원 등록
  redis.call('ZADD', passZKey, expireAt, userId)

  -- 4) token 발급 + TTL
  local seq = redis.call('INCR', tokenSeqKey)
  local token = tostring(nowMs) .. ":" .. tostring(seq) .. ":" .. userId
  redis.call('SET', tokenKeyPrefix .. userId, token, 'PX', passTtlMs)

  advanced = advanced + 1
end

return advanced
