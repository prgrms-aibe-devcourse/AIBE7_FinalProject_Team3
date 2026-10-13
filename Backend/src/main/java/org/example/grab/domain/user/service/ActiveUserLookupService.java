package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/*
    토큰 재발급(GR-35 M00-04)에서 Refresh 세션의 users.id로 회원을 다시 읽는다.
    세션에는 내부 ID만 있어 JWT sub(public_id)·roles를 만들려면 조회가 필요하고, 로그인 후의 정지·탈퇴·역할 변경을 여기서 반영한다.
    회원이 없거나 ACTIVE가 아니면 빈 결과를 돌려준다. 둘을 구분하지 않는 이유는 재발급 응답이 INVALID_TOKEN 하나이기 때문이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActiveUserLookupService {

    private final UserRepository userRepository;

    public Optional<LoginUser> findActive(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(LoginUser::from);
    }
}
