package org.example.grab.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.auth.dto.internal.LoginResult;
import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.service.UserCredentialService;
import org.springframework.stereotype.Service;

/*
    LOCAL 로그인(MEMBER_AUTH.md 1.3). 자격 확인 뒤 세션 발급은 소셜 로그인과 같은 AuthSessionIssuer가 한다(GR-46 M00-08).
    - 실패(INVALID_CREDENTIALS·ACCOUNT_DISABLED)는 발급 전에 끝나 토큰을 만들지 않고 Redis에 저장하지 않는다.
      GlobalExceptionHandler가 오류 코드만 로그로 남긴다
    - Redis 저장 실패는 감싸지 않아 500 INTERNAL_SERVER_ERROR가 되고 쿠키는 나가지 않는다
    서비스 트랜잭션을 두지 않는다. 회원·판매자 조회는 각자 끝나고, Redis 저장은 DB 트랜잭션과 묶이지 않는다.
    이메일·비밀번호·토큰은 로그에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class LoginService {

    private final UserCredentialService userCredentialService;
    private final AuthSessionIssuer authSessionIssuer;

    // normalizedEmail은 요청 DTO에서 정규화한 값, rawPassword는 가공하지 않은 원문이다
    public LoginResult login(String normalizedEmail, String rawPassword) {
        LoginUser user = userCredentialService.authenticate(normalizedEmail, rawPassword);
        return authSessionIssuer.issue(user);
    }
}
