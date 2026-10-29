package org.example.grab.domain.user.service;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.security.identity.UserIdResolver;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/*
    global/security/identity의 UserIdResolver 구현. 인증된 principal의 public_id를 users.id로 바꾼다(M00-02).
    인증 요청마다 불려 요청당 DB 왕복의 절반을 차지해 앱 메모리 캐시를 둔다(GR-201 측정, GR-211).
    - 대응이 바뀌지 않고 회원 행은 지우지 않으므로(탈퇴는 상태값) 무효화하지 않는다. 상태 판정은 각 서비스가 한다.
    - 트랜잭션을 두지 않는다. 캐시 적중이면 DB에 가지 않고, 누락이면 SELECT 한 문장뿐이라 자동 커밋으로 충분하다
      (BEGIN·COMMIT 왕복을 줄임, GR-201 후보 B).
    - 있는 회원만 저장한다. 없는 회원은 저장하지 않아 다음 호출도 DB를 조회한다.
 */
@Service
@RequiredArgsConstructor
public class UserIdLookupService implements UserIdResolver {

    private final UserRepository userRepository;
    private final Cache<UUID, Long> userIdCache;

    @Override
    public Optional<Long> resolve(UUID publicId) {
        Long cachedId = userIdCache.getIfPresent(publicId); // publicId로 캐시 조회 -> 있으면 값 불러오고 없으면 null
        if (cachedId != null) { // 회원이 캐시에 있으면 내부 ID
            return Optional.of(cachedId);
        }
        // 캐시에 없으면 DB에서 불러오고
        Optional<Long> userId = userRepository.findIdByPublicId(publicId);
        // DB에도 해당 회원 없으면 비어있는 optional 객체 반환
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        // 여기서 캐시 저장
        userIdCache.put(publicId, userId.get());
        return userId;
    }
}
