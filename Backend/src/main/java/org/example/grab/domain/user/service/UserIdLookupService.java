package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.security.identity.UserIdResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/*
    global/security/identity의 UserIdResolver 구현. 인증된 principal의 public_id를 users.id로 바꾼다(M00-02).
    캐시는 우선 두지 않고 추후 부하 테스트에서 병목이 확인되면 이 클래스에만 로컬 캐시를 추가 고민(GR-32 notes 3절).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserIdLookupService implements UserIdResolver {

    private final UserRepository userRepository;

    @Override
    public Optional<Long> resolve(UUID publicId) {
        return userRepository.findIdByPublicId(publicId);
    }
}
