-- GR-161: 로그인 폭주 측정이 남긴 k6 계정의 로그인 세션만 지운다. 다른 회원의 세션은 건드리지 않는다.
-- 로그인 1건은 refresh-token-save.lua로 키 2개를 남긴다(TTL 14일).
--   auth:refresh-token:{토큰 해시}   = {"userId":..,"sessionId":"..","sessionExpiresAt":".."}
--   auth:refresh-session:{sessionId} = 토큰 해시
-- 토큰 키 값의 userId로 대상을 골라, 그 토큰 키와 같은 sessionId의 세션 키를 함께 지운다.
-- 패턴 auth:refresh-token:* 은 재사용 표식 auth:refresh-token-used:* 와 겹치지 않는다(':' 와 '-' 차이).
-- 스크립트가 도는 동안 Redis는 다른 명령을 받지 않는다. 로컬 측정 뒤 한 번 실행하는 용도로만 쓴다.
-- 키 이름을 KEYS로 미리 넘길 수 없어 Redis Cluster에서는 쓸 수 없다(GRAB은 단일 Redis).
-- ARGV[1]: 대상 회원의 users.id
-- 반환: 지운 세션 수
local userId = tonumber(ARGV[1])
if not userId then
    return redis.error_reply('ARGV[1]에 users.id(숫자)를 넘겨야 합니다')
end

local cursor = '0'
local deleted = 0
repeat
    local page = redis.call('SCAN', cursor, 'MATCH', 'auth:refresh-token:*', 'COUNT', 1000)
    cursor = page[1]
    for _, tokenKey in ipairs(page[2]) do
        local value = redis.call('GET', tokenKey)
        -- 읽을 수 없는 값은 대상 여부를 알 수 없으므로 남긴다
        local ok, session = pcall(cjson.decode, value or '')
        if ok and type(session) == 'table' and tonumber(session.userId) == userId then
            redis.call('DEL', tokenKey)
            if type(session.sessionId) == 'string' then
                redis.call('DEL', 'auth:refresh-session:' .. session.sessionId)
            end
            deleted = deleted + 1
        end
    end
until cursor == '0'

return deleted
