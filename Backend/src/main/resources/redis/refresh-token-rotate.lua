-- Refresh Token 교체(GR-35 M00-01·02). 옛 토큰 키가 아직 있을 때만 새 토큰으로 바꾼다.
-- 같은 토큰의 동시 요청은 스크립트가 차례로 실행되므로 DEL이 1을 돌려준 요청 하나만 교체한다.
-- 옛 키 삭제와 새 키 저장이 한 번에 일어나 두 토큰이 동시에 유효한 순간이 없다.
-- KEYS[1]: 옛 토큰 키, KEYS[2]: 새 토큰 키, KEYS[3]: 옛 토큰의 사용됨 표식 키, KEYS[4]: 세션 키
-- ARGV[1]: 세션 JSON, ARGV[2]: 새 토큰 해시, ARGV[3]: TTL(초), ARGV[4]: sessionId
-- 반환: 1 교체함, 0 옛 토큰 키 없음(다른 요청이 먼저 교체했거나 세션이 폐기됨)
if redis.call('DEL', KEYS[1]) == 0 then
    return 0
end

-- 새 Refresh Token 키 저장
redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[3])
-- 현재 세션이 사용하고 있는 Refresh Token 저장
redis.call('SET', KEYS[4], ARGV[2], 'EX', ARGV[3])

-- 유예 판정은 Redis 시계로 한다.
local time = redis.call('TIME')
-- 토큰을 교체한 시각을 밀리초 숫자로 표현한 값
local rotatedAt = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
-- auth:refresh-token-used:<A의 해시> 형태로 사용 토큰 저장
--   ├─ sessionId: S1                 ← 사용된 토큰이 속한 로그인 세션
--   └─ rotatedAt: 1700000000123      ← A교체한 시각
redis.call('HSET', KEYS[3], 'sessionId', ARGV[4], 'rotatedAt', rotatedAt)
-- 사용 기록을 ttl 시간 후에 삭제
redis.call('EXPIRE', KEYS[3], ARGV[3])
return 1
