package org.example.grab.global.security.jwt;

import org.example.grab.global.security.AuthenticatedUser;

import java.util.UUID;

/*
    검증을 통과한 Access Token의 주인. SecurityContext의 principal로 들어간다.
    토큰의 sub(users.public_id)만 담고, 내부 ID(users.id)는 필요할 때 CurrentUserIdProvider가 변환한다(M00-02).
 */
public record AccessTokenPrincipal(UUID publicId) implements AuthenticatedUser {

    public AccessTokenPrincipal(UUID publicId) {
        if (publicId == null) {
            throw new IllegalArgumentException("publicId는 비어 있을 수 없습니다.");
        }
        this.publicId = publicId;
    }
}
