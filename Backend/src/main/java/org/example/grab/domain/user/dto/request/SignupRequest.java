package org.example.grab.domain.user.dto.request;

// 역할·상태·가입 경로는 서버가 정하므로 입력으로 받지 않는다(MEMBER_AUTH.md 1.2)
public record SignupRequest(
        String email,
        String password,
        String nickname
) {
}
