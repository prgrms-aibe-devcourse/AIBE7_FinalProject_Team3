-- 없는 토큰이 교체된 적 있는지 확인하고, 유예가 지났으면 그 세션을 폐기한다(GR-35 M00-02·03).
-- 확인과 폐기를 한 번에 해, 그 사이에 같은 세션이 교체되어도 폐기 시점의 현재 토큰을 지운다.
-- 세션 키와 현재 토큰 키는 표식·세션 키의 값으로 만들므로 KEYS에 미리 넣을 수 없다.
-- 그래서 Redis Cluster에서는 쓸 수 없다(GRAB은 단일 Redis). 접두사는 RefreshTokenHasher에서 받는다.
-- KEYS[1]: 사용됨 표식 키
-- ARGV[1]: 유예(밀리초), ARGV[2]: 토큰 키 접두사, ARGV[3]: 세션 키 접두사
-- 반환: {'NOT_FOUND'} | {'WITHIN_GRACE', sessionId} | {'REUSE_DETECTED', sessionId}

-- 옛 토큰의 사용 기록에서 sessionId와 rotatedAt 두 값을 읽어 marker에 담는 코드
local marker = redis.call('HMGET', KEYS[1], 'sessionId', 'rotatedAt')
local sessionId, rotatedAt = marker[1], marker[2]
-- 옛 토큰 사용 기록에 sessionId나 rotatedAt 없으면 NOT_FOUND 반환
if not sessionId or not rotatedAt then
    return {'NOT_FOUND'}
end

local time = redis.call('TIME')
-- 현재 시각을 밀리초로 변환
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
-- 토큰 교체 후 지난 시간이 유예 시간보다 짧을 때 반환
if now - tonumber(rotatedAt) < tonumber(ARGV[1]) then
    return {'WITHIN_GRACE', sessionId}
end

-- refresh-session-revoke.lua와 같은 폐기 절차, ..은 문자열을 이어 붙이는 연산자
local sessionKey = ARGV[3] .. sessionId
-- 세션 키에서 현재 토큰의 해시를 읽음
local currentHash = redis.call('GET', sessionKey)
-- 사용중인 Refresh Token, 세션 키 삭제
if currentHash then
    redis.call('DEL', ARGV[2] .. currentHash)
end
redis.call('DEL', sessionKey)
return {'REUSE_DETECTED', sessionId}
