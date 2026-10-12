package org.example.grab.domain.user.service;

import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.error.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/*
    LOCAL 로그인의 회원 조회·비밀번호 검증·상태 판정(MEMBER_AUTH.md 1.3, GR-34 M00-03).
    판정 순서는 조회 → 비밀번호 검증 → 상태 확인이다. 비활성 계정은 비밀번호를 아는 사람에게만 ACCOUNT_DISABLED로 드러난다.
    서비스 트랜잭션을 두지 않는다. 회원 조회가 끝나면 DB 연결을 돌려주고 Argon2 검증을 한다(SignupService와 같은 이유).
    비밀번호 원문·해시와 이메일은 로그와 예외 메시지에 남기지 않는다(NFR-001).
 */
@Service
public class UserCredentialService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    // 검증할 해시가 없는 경우에도 Argon2 검증을 한 번 실행해 응답 시간으로 가입 여부가 드러나지 않게 한다.
    // 같은 인코더로 만들어 실제 해시와 파라미터(검증 시간)가 같고, 인코더 설정을 바꿔도 따라간다. 원문은 버리므로 어떤 입력과도 일치하지 않는다
    private final String dummyPasswordHash;

    public UserCredentialService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /*
        normalizedEmail은 요청 DTO에서 EmailNormalizer로 정규화한 값이다. rawPassword는 가공하지 않은 원문이다.
        - 회원 없음·LOCAL이 아닌 회원·해시 없음: 더미 해시로 검증한 뒤 INVALID_CREDENTIALS
        - 비밀번호 불일치: 상태와 관계없이 INVALID_CREDENTIALS
        - 비밀번호 일치 + ACTIVE가 아님(SUSPENDED·WITHDRAWN): ACCOUNT_DISABLED
     */
    public LoginUser authenticate(String normalizedEmail, String rawPassword) {
        User user = userRepository.findByEmail(normalizedEmail)
                .filter(UserCredentialService::hasLocalPassword)
                .orElse(null);

        if (user == null) {
            // 결과는 쓰지 않는다. 회원이 있을 때와 같은 시간을 쓰기 위한 호출이다
            passwordEncoder.matches(rawPassword, dummyPasswordHash);
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }
        return LoginUser.from(user);
    }

    // 소셜 회원은 비밀번호 해시가 없다. matches(raw, null)은 Argon2 계산 없이 끝나 응답 시간이 달라지므로 미리 거른다
    private static boolean hasLocalPassword(User user) {
        return user.getProvider() == AuthProvider.LOCAL && user.getPasswordHash() != null;
    }
}
