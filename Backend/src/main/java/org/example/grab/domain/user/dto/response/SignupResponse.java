package org.example.grab.domain.user.dto.response;

import org.example.grab.domain.user.entity.User;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/*
    회원가입 완료 응답(MEMBER_AUTH.md 1.2.3). 엔티티·비밀번호 해시는 내보내지 않는다.
    userId는 내부 id가 아니라 public_id다. 가입 직후 역할은 USER 하나다.
 */
public record SignupResponse(
        UUID userId,
        String email,
        String nickname,
        List<String> roles,
        OffsetDateTime createdAt
) {

    public static SignupResponse from(User user) {
        return new SignupResponse(
                user.getUuid(),
                user.getEmail(),
                user.getNickname(),
                List.of(user.getRole().name()),
                user.getCreatedAt()
        );
    }
}
