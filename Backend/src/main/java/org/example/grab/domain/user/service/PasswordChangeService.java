package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/*
    내 비밀번호 변경(MEMBER_AUTH.md 1.11, GR-129). 사용자 ID는 토큰의 본인으로 정하고 경로·쿼리로 받지 않는다.
    판정 순서는 회원 상태 → 소셜 회원 → 현재 비밀번호 불일치 → 새 비밀번호가 현재와 같음 → 저장이다(M00-04).
    서비스 트랜잭션을 두지 않는다. 조회가 끝나면 DB 연결을 돌려주고 Argon2 검증·해시를 한 뒤, 조건부 UPDATE 한 문장으로 저장한다
    (UserCredentialService와 같은 이유, M03-02). 그 사이 비밀번호가 바뀌었으면 저장이 0행이 되어 PASSWORD_MISMATCH로 응답한다.
    기존 로그인 세션은 종료하지 않는다(M00-03). 비밀번호 원문·해시와 이메일은 로그와 예외 메시지에 남기지 않는다(NFR-001).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // 두 비밀번호는 가공하지 않은 원문이다. newPassword는 요청 DTO에서 비밀번호 규칙 검증을 마쳤다
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> rejected("user_not_found", userId));
        // 정지·탈퇴 회원은 내 정보 조회(1.9)와 같이 AUTHENTICATION_REQUIRED로 거부한다(GR-97 M00-03)
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw rejected("user_inactive", userId);
        }
        String readHash = user.getPasswordHash();
        // 소셜 회원은 비밀번호 해시가 없다. matches(raw, null)을 부르기 전에 거른다
        if (user.getProvider() != AuthProvider.LOCAL || readHash == null) {
            throw new BusinessException(UserErrorCode.PASSWORD_NOT_AVAILABLE);
        }
        if (!passwordEncoder.matches(currentPassword, readHash)) {
            throw new BusinessException(UserErrorCode.PASSWORD_MISMATCH);
        }
        // 현재 비밀번호가 저장된 해시와 맞는 것을 확인한 뒤라 원문끼리 비교하면 된다. 확인 전에 판단하면
        // 현재 비밀번호를 모르는 요청에 같은 값인지가 드러나므로 이 순서를 지킨다(M00-04)
        if (newPassword.equals(currentPassword)) {
            throw new BusinessException(UserErrorCode.PASSWORD_UNCHANGED);
        }

        String newHash = passwordEncoder.encode(newPassword);
        // 엔티티 대신 서비스가 빈 해시 저장을 막는다(M03-01 A안). 인코더 오류라 요청 오류로 바꾸지 않는다
        if (newHash == null || newHash.isBlank()) {
            throw new IllegalStateException("비밀번호 해시를 만들지 못했습니다.");
        }
        if (userRepository.updatePasswordHashIfUnchanged(userId, readHash, newHash) == 0) {
            log.info("비밀번호 변경 거부: reason=password_changed_concurrently, userId={}", userId);
            throw new BusinessException(UserErrorCode.PASSWORD_MISMATCH);
        }
        log.info("비밀번호 변경: userId={}", userId);
    }

    // 1.9 조회 거부(UserMeQueryService)와 같이 사유와 내부 ID만 남긴다
    private static BusinessException rejected(String reason, Long userId) {
        log.info("비밀번호 변경 거부: reason={}, userId={}", reason, userId);
        return new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
