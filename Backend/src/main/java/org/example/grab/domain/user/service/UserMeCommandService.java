package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.user.dto.response.UserMeResponse;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.AuthRole;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/*
    내 닉네임 수정(MEMBER_AUTH.md 1.10, GR-129). 사용자 ID는 토큰의 본인으로 정하고 경로·쿼리로 받지 않는다.
    판정 순서는 회원 상태 → 현재와 같은 값 → 다른 회원과 중복 → 저장이다(M00-05).
    응답은 내 정보 조회(1.9)와 같은 UserMeResponse이고, roles는 요청 Access Token의 권한을 받는다(M00-02).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMeCommandService {

    // V6 마이그레이션의 닉네임 유니크 인덱스 이름(SignupService·SocialAccountService와 같음)
    private static final String NICKNAME_UNIQUE_CONSTRAINT = "uq_users_nickname_lower";

    private final UserRepository userRepository;

    /*
        nickname은 요청 DTO에서 앞뒤 공백을 제거하고 규칙 검증을 마친 값이다.
        - 정지·탈퇴 회원은 1.9와 같이 AUTHENTICATION_REQUIRED로 거부한다(GR-97 M00-03)
        - 저장된 닉네임과 대소문자까지 같으면 NICKNAME_UNCHANGED다. 대소문자만 다르면 변경으로 본다
        - 중복 사전 검사는 본인 행을 빼고 대소문자를 무시해 비교한다
        - 동시 변경은 사전 검사를 함께 통과할 수 있어 flush로 유니크 위반을 이 트랜잭션 안에서 확인한다
     */
    @Transactional
    public UserMeResponse changeNickname(Long userId, Set<AuthRole> roles, String nickname) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> rejected("user_not_found", userId));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw rejected("user_inactive", userId);
        }
        if (user.getNickname().equals(nickname)) {
            throw new BusinessException(UserErrorCode.NICKNAME_UNCHANGED);
        }
        if (userRepository.existsByNicknameIgnoreCaseExcludingUser(nickname, userId)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_NICKNAME);
        }

        user.changeNickname(nickname);
        flushNickname();
        return UserMeResponse.of(user, roles);
    }

    /*
        변경 감지는 커밋 때 UPDATE를 보내므로, 그때 난 유니크 위반은 이 메서드 밖에서 터져 DUPLICATE_NICKNAME으로 바꿀 수 없다.
        여기서 먼저 flush해 사전 검사와 같은 응답으로 바꾼다. 다른 무결성 위반은 중복으로 숨기지 않고 그대로 던진다.
        원래 예외에는 SQL과 입력값이 담기므로 BusinessException에 원인으로 연결하지 않는다(SignupService와 같음).
     */
    private void flushNickname() {
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (violates(e, NICKNAME_UNIQUE_CONSTRAINT)) {
                throw new BusinessException(UserErrorCode.DUPLICATE_NICKNAME);
            }
            throw e;
        }
    }

    // Hibernate가 DB 오류에서 꺼낸 제약 이름을 비교한다
    private static boolean violates(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    // 1.9 조회 거부(UserMeQueryService)와 같이 닉네임·이메일 등 개인정보는 남기지 않고 사유와 내부 ID만 남긴다
    private static BusinessException rejected(String reason, Long userId) {
        log.info("내 닉네임 수정 거부: reason={}, userId={}", reason, userId);
        return new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
