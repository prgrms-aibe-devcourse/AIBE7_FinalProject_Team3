package org.example.grab.global.security.jwt;

import java.util.UUID;

/*
    검증을 통과한 Access Token의 주인. SecurityContext의 principal로 들어간다.
    토큰의 sub(users.public_id)만 담고, 내부 ID(users.id)는 필요할 때 CurrentUserIdProvider가 변환한다(M00-02).
    TODO(M03-05-1): AuthenticatedUser를 UUID publicId() 계약으로 바꾸고 이 record가 구현하도록 연결한다.
 */
public record AccessTokenPrincipal(UUID publicId) {

    public AccessTokenPrincipal(UUID publicId) {
        if (publicId == null) {
            throw new IllegalArgumentException("publicId는 비어 있을 수 없습니다.");
        }
        this.publicId = publicId;
    }
}
