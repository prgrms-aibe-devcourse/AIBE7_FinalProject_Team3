package org.example.grab.domain.user.dto.internal;

import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserRole;

import java.util.UUID;

/**
 * 자격 확인을 통과한 회원 정보. 로그인(auth 도메인)에 넘기며 회원 엔티티와 비밀번호 해시는 user 도메인 밖으로 내보내지 않는다(GR-34 M00-01).
 *
 * @param id       회원 내부 ID(Refresh 세션의 userId). 응답·토큰에는 넣지 않는다
 * @param publicId 외부 노출 회원 ID(JWT sub, 응답 userId)
 * @param email    정규화해 저장된 이메일
 * @param nickname 닉네임
 * @param role     회원 역할. SELLER는 여기에 없고 발급 시점에 따로 판정한다
 */
public record LoginUser(
        Long id,
        UUID publicId,
        String email,
        String nickname,
        UserRole role
) {

    public static LoginUser from(User user) {
        return new LoginUser(
                user.getId(),
                user.getUuid(),
                user.getEmail(),
                user.getNickname(),
                user.getRole()
        );
    }
}
