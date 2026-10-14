package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.user.dto.response.UserMeResponse;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.AuthRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/*
    내 정보 조회(MEMBER_AUTH.md 1.9, GR-97). 회원 정보는 매번 DB에서 읽고, roles는 요청 Access Token의 권한을 받는다(M00-01).
    JWT는 회원 상태를 보지 않으므로 로그인 후 정지·탈퇴된 회원도 Access 만료까지 인증된다. 여기서 ACTIVE가 아니면
    회원이 없을 때와 같이 AUTHENTICATION_REQUIRED로 거부한다(M00-03). 계정 상태는 토큰만으로 드러내지 않고 로그인에서 안내한다.
    클라이언트는 이 401로 재발급을 시도하고, 재발급이 회원 상태를 확인해 세션을 폐기한다(GR-35 M00-04).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserMeQueryService {

    private final UserRepository userRepository;

    // userId는 CurrentUserIdProvider가 principal의 public_id로 찾은 내부 ID다
    public UserMeResponse findMe(Long userId, Set<AuthRole> roles) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> rejected("user_not_found", userId));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw rejected("user_inactive", userId);
        }
        return UserMeResponse.of(user, roles);
    }

    // 이메일 등 개인정보는 남기지 않고 사유와 내부 ID만 남긴다
    private static BusinessException rejected(String reason, Long userId) {
        log.info("내 정보 조회 거부: reason={}, userId={}", reason, userId);
        return new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
