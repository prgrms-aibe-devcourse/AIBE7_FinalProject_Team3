package org.example.grab.global.security.identity;

import java.util.Optional;
import java.util.UUID;

/*
    회원 public_id를 내부 ID(users.id)로 바꾼다. 외부(JWT·API)는 public_id, 내부(서비스·FK)는 users.id를 쓴다
    global은 domain을 참조하지 않으므로 인터페이스만 두고 구현은 user 도메인에 둔다.
    병목이 확인되면 구현체에 로컬 캐시를 둘 수 있다. public_id → id 대응은 바뀌지 않는다
 */
public interface UserIdResolver {

    Optional<Long> resolve(UUID publicId);
}
