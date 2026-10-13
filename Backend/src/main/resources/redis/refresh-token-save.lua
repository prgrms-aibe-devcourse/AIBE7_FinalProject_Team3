-- Refresh Token 저장(GR-35 M01-03). 토큰 키와 세션 키를 같은 TTL로 함께 쓴다.
-- 스크립트는 다른 명령과 섞이지 않고 실행되므로 한쪽 키만 저장된 상태가 다른 요청에 보이지 않는다.
-- KEYS[1]: auth:refresh-token:{해시}, KEYS[2]: auth:refresh-session:{sessionId}
-- ARGV[1]: 세션 JSON, ARGV[2]: 토큰 해시, ARGV[3]: TTL(초)
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
return 1
