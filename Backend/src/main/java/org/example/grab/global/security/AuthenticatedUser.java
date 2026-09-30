package org.example.grab.global.security;

import java.util.UUID;

/*
    인증된 회원 principal의 계약. 외부 식별자인 users.public_id만 가진다(M00-02).
    내부 ID(users.id)는 CurrentUserIdProvider가 UserIdResolver로 필요할 때 변환한다.
 */
public interface AuthenticatedUser {

    UUID publicId();
}
