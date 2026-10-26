package org.example.grab.domain.user.dto.response;

import org.example.grab.domain.user.entity.User;

import java.util.UUID;

/*
    내 프로필 이미지 등록/수정 응답(MEMBER_AUTH.md 1.12). 명세대로 두 필드만 둔다(GR-130 M00-05).
    1.10(내 정보 수정)과 달리 프로필 이미지 하위 자원만 다루므로, 클라이언트는 내 정보 캐시의 profileImageUrl만 바꾼다.
    userId는 내부 id가 아니라 public_id이고, profileImageUrl은 저장 값 그대로다(M00-03).
 */
public record ProfileImageResponse(
        UUID userId,
        String profileImageUrl
) {

    public static ProfileImageResponse from(User user) {
        return new ProfileImageResponse(user.getUuid(), user.getProfileImageUrl());
    }
}
