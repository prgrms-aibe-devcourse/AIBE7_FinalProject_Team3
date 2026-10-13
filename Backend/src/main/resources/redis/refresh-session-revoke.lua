-- 세션 폐기(GR-35 M00-02·04). 세션 키가 가리키는 현재 토큰 키와 세션 키를 함께 지운다.
-- 조회와 삭제를 한 번에 해, 그 사이에 교체가 끼어들어 새 토큰이 남는 일이 없다.
-- 현재 토큰 키는 세션 키의 값으로 만들므로 Redis Cluster에서는 쓸 수 없다(GRAB은 단일 Redis).
-- KEYS[1]: 세션 키 / ARGV[1]: 토큰 키 접두사
-- 반환: 1(세션 키가 없어도 멱등하게 1)
-- 세션 키에서 현재 토큰의 해시를 찾은 뒤, 해당 토큰 키와 세션 키를 삭제
local currentHash = redis.call('GET', KEYS[1])
if currentHash then
    redis.call('DEL', ARGV[1] .. currentHash)
end
redis.call('DEL', KEYS[1])
return 1
