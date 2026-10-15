package org.example.grab.domain.user.dto.response;

import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.example.grab.global.security.AuthRole;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/*
    내 정보 조회 응답(MEMBER_AUTH.md 1.9). 엔티티·내부 id·status는 내보내지 않는다.
    userId는 내부 id가 아니라 public_id다. profileImageUrl은 등록된 이미지가 없으면 null이고 키는 생략하지 않는다(GR-97 M00-05).
    createdAt은 DB에서 읽은 값을 변환하지 않는다. TIMESTAMPTZ는 오프셋을 저장하지 않아 표기는 읽는 쪽 설정을 따른다(M00-05).
 */
public record UserMeResponse(
        UUID userId,
        String email,
        String nickname,
        // 가입 방식. 화면이 LOCAL 회원에게만 비밀번호 변경(1.11)을 보여 주는 데 쓴다(GR-108). enum이라 OpenAPI에 허용값이 나간다
        AuthProvider provider,
        // 요청 Access Token의 권한이다(M00-01). 로그인 응답·JWT Claim과 같은 AuthRole 선언 순서로 정렬한다
        List<String> roles,
        String profileImageUrl,
        OffsetDateTime createdAt
) {

    public static UserMeResponse of(User user, Collection<AuthRole> roles) {
        return new UserMeResponse(
                user.getUuid(),
                user.getEmail(),
                user.getNickname(),
                user.getProvider(),
                roles.stream().distinct().sorted().map(AuthRole::name).toList(),
                user.getProfileImageUrl(),
                user.getCreatedAt()
        );
    }
}
