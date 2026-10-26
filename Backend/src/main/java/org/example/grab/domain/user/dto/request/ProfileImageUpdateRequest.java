package org.example.grab.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/*
    내 프로필 이미지 등록/수정 요청(MEMBER_AUTH.md 1.12, GR-130). 명세대로 imageUrl만 받는다(M00-02).
    누락·null·빈 값은 VALIDATION_FAILED다(M00-06). 본인 발급 URL과 문자열 전체를 비교하므로 공백을 제거하지 않는다.
    본인 발급 여부는 서비스(ProfileImageUrlVerifier)가 판단한다.
 */
public record ProfileImageUpdateRequest(
        @NotBlank(message = "프로필 이미지 URL은 필수입니다.") String imageUrl
) {
}
