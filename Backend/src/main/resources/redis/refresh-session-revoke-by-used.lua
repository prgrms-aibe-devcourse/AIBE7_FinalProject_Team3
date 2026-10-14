-- 교체된 토큰으로 로그아웃할 때 그 토큰의 세션을 폐기한다(GR-36 M00-02).
-- 재사용 탐지(refresh-token-reuse.lua)와 달리 유예 시간과 관계없이 폐기한다. 로그아웃은 사용자가 세션 종료를 요청한 것이기 때문이다.
-- 표식 조회와 폐기를 한 번에 하기 때문에 교체와 폐기가 엇갈려 새 토큰이 살아남는 일을 방지할 수 있음
-- 세션 키와 현재 토큰 키는 표식·세션 키의 값으로 만들므로 Redis Cluster에서는 쓸 수 없다(GRAB은 단일 Redis).
-- KEYS[1]: 사용됨 표식 키
-- ARGV[1]: 토큰 키 접두사, ARGV[2]: 세션 키 접두사
-- 반환: 폐기한 sessionId, 표식이 없으면 nil
local sessionId = redis.call('HGET', KEYS[1], 'sessionId')
if not sessionId then
    return nil
end

-- refresh-session-revoke.lua와 같은 폐기 절차. 사용 표식은 지우지 않고 TTL로 만료시킨다
local sessionKey = ARGV[2] .. sessionId
local currentHash = redis.call('GET', sessionKey)
if currentHash then
    redis.call('DEL', ARGV[1] .. currentHash)
end
redis.call('DEL', sessionKey)
return sessionId
