package org.example.grab.domain.auth.dto.response;

import org.example.grab.domain.auth.dto.internal.LoginResult;
import org.example.grab.global.security.AuthRole;

import java.util.List;
import java.util.UUID;

/*
    LOCAL 로그인 응답 본문(MEMBER_AUTH.md 1.3). data.user 아래에 회원 정보만 담는다.
    토큰은 HttpOnly 쿠키로만 보내고 본문에 넣지 않는다(COMMON 1.2). userId는 내부 id가 아니라 public_id다.
 */
public record LoginResponse(UserInfo user) {

    public record UserInfo(
            UUID userId,
            String email,
            String nickname,
            // JWT roles Claim과 같은 값·순서(AuthRole 선언 순서)
            List<String> roles
    ) {
    }

    public static LoginResponse from(LoginResult result) {
        return new LoginResponse(new UserInfo(
                result.user().publicId(),
                result.user().email(),
                result.user().nickname(),
                result.roles().stream().map(AuthRole::name).toList()
        ));
    }
}
