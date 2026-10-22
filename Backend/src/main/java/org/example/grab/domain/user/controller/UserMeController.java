package org.example.grab.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.upload.dto.ImageUploadUrlRequest;
import org.example.grab.domain.drop.upload.dto.ImageUploadUrlResponse;
import org.example.grab.domain.user.dto.request.PasswordChangeRequest;
import org.example.grab.domain.user.dto.request.ProfileImageUpdateRequest;
import org.example.grab.domain.user.dto.request.UserNicknameUpdateRequest;
import org.example.grab.domain.user.dto.response.ProfileImageResponse;
import org.example.grab.domain.user.dto.response.UserMeResponse;
import org.example.grab.domain.user.service.PasswordChangeService;
import org.example.grab.domain.user.service.ProfileImageCommandService;
import org.example.grab.domain.user.service.ProfileImageUploadService;
import org.example.grab.domain.user.service.UserMeCommandService;
import org.example.grab.domain.user.service.UserMeQueryService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/*
    내 정보 조회(MEMBER_AUTH.md 1.9, GR-97). 사용자 ID는 경로·쿼리로 받지 않고 토큰의 본인으로 조회한다.
    roles는 요청 Access Token의 권한을 그대로 쓴다(M00-01). 화면 권한이 서버가 지금 실제로 허용하는 권한과 같고,
    판매자 승인·취소는 다음 재발급부터 반영된다. 즉시 반영이 필요하면 클라이언트가 재발급 후 다시 조회한다.
    닉네임 수정(1.10, GR-129)도 같은 응답을 돌려줘 클라이언트가 응답으로 내 정보를 그대로 바꿀 수 있다(M00-02).
    비밀번호 변경(1.11, GR-129)은 본문 없이 204로 응답하고 기존 로그인 세션을 종료하지 않는다(M00-03).
    프로필 이미지 업로드 URL 발급(GR-130)은 SELLER 전용 DROP 이미지 발급과 같은 요청·응답 DTO를 쓰고(M02-01),
    본인 public_id가 들어간 경로로만 발급한다(M00-02).
    프로필 이미지 등록/수정(1.12)은 본인이 발급받은 URL만 받고 { userId, profileImageUrl }로 응답하며(M00-05),
    삭제(1.13)는 204로 응답한다. 둘 다 교체·삭제한 이전 객체를 커밋 후 지운다(M00-04).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me")
@Tag(name = "내 정보", description = "로그인한 사용자의 회원 정보 조회·수정, 비밀번호 변경, 프로필 이미지 업로드 URL 발급·등록·삭제")
public class UserMeController {

    private final UserMeQueryService userMeQueryService;
    private final UserMeCommandService userMeCommandService;
    private final PasswordChangeService passwordChangeService;
    private final ProfileImageUploadService profileImageUploadService;
    private final ProfileImageCommandService profileImageCommandService;
    private final CurrentUserIdProvider currentUserIdProvider;

    @Operation(summary = "내 정보 조회",
            description = "본인의 회원 정보를 반환한다. provider는 가입 방식, roles는 요청 Access Token의 권한이고, profileImageUrl은 없으면 null이다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "AUTHENTICATION_REQUIRED(쿠키 없음, 회원 없음·정지·탈퇴), INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR")
    @GetMapping
    public ApiResponse<UserMeResponse> getMe(@Parameter(hidden = true) Authentication authentication) {
        return ApiResponse.success(
                userMeQueryService.findMe(currentUserIdProvider.currentUserId(), rolesOf(authentication)));
    }

    @Operation(summary = "내 닉네임 수정",
            description = "본인의 닉네임을 바꾸고 내 정보 조회와 같은 응답을 반환한다. 앞뒤 공백을 제거해 저장하며, "
                    + "현재 닉네임과 대소문자만 다른 값은 허용하고 대소문자까지 같은 값은 거부한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공, 내 정보 조회와 같은 7개 필드")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "INVALID_REQUEST, VALIDATION_FAILED, INVALID_NICKNAME, NICKNAME_UNCHANGED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "AUTHENTICATION_REQUIRED(쿠키 없음, 회원 없음·정지·탈퇴), INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED(USER 권한 없음, CSRF 토큰 없음·불일치)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_NICKNAME")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR")
    @PatchMapping
    public ApiResponse<UserMeResponse> changeNickname(
            @Parameter(hidden = true) Authentication authentication,
            @Valid @RequestBody UserNicknameUpdateRequest request) {
        return ApiResponse.success(userMeCommandService.changeNickname(
                currentUserIdProvider.currentUserId(), rolesOf(authentication), request.nickname()));
    }

    @Operation(summary = "내 비밀번호 변경",
            description = "LOCAL 회원이 현재 비밀번호를 확인한 뒤 새 비밀번호로 바꾼다. 현재 비밀번호는 비밀번호 규칙을 검사하지 않는다. "
                    + "기존 로그인 세션(이 요청의 세션 포함)은 종료하지 않는다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "변경 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "INVALID_REQUEST, VALIDATION_FAILED, INVALID_PASSWORD, PASSWORD_MISMATCH(현재 비밀번호 불일치), "
                    + "PASSWORD_NOT_AVAILABLE(KAKAO·GOOGLE 회원), PASSWORD_UNCHANGED(새 비밀번호가 현재와 같음)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "AUTHENTICATION_REQUIRED(쿠키 없음, 회원 없음·정지·탈퇴), INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED(USER 권한 없음, CSRF 토큰 없음·불일치)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR")
    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        passwordChangeService.changePassword(
                currentUserIdProvider.currentUserId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 프로필 이미지 업로드 URL 발급",
            description = "받은 uploadUrl로 클라이언트가 직접 PUT 업로드하고, imageUrl을 프로필 이미지 등록 요청에 쓴다. "
                    + "요청·응답은 DROP 이미지 업로드 URL 발급과 같다. 형식은 image/jpeg·image/png·image/webp, 크기는 5MB 이하. "
                    + "uploadUrl은 발급 후 2시간 동안 유효하다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "INVALID_REQUEST, VALIDATION_FAILED(fileName·contentType·fileSize)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "AUTHENTICATION_REQUIRED(쿠키 없음, 회원 없음·정지·탈퇴), INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED(USER 권한 없음, CSRF 토큰 없음·불일치)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_SERVICE_ERROR")
    @PostMapping("/profile-image/upload-url")
    public ApiResponse<ImageUploadUrlResponse> issueProfileImageUploadUrl(
            @Valid @RequestBody ImageUploadUrlRequest request) {
        return ApiResponse.success(profileImageUploadService.issue(
                currentUserIdProvider.currentUserId(), request.contentType(), request.fileSize()));
    }

    @Operation(summary = "내 프로필 이미지 등록/수정",
            description = "프로필 이미지 업로드 URL 발급으로 받은 본인의 imageUrl을 그대로 등록한다. 다른 회원 경로·DROP 이미지·외부 URL은 거부하며, "
                    + "업로드 여부는 확인하지 않는다. 현재와 같은 URL도 성공하고, 바뀐 경우 이전 이미지는 커밋 후 삭제한다. "
                    + "응답은 userId(public_id)와 profileImageUrl 두 필드다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "INVALID_REQUEST, VALIDATION_FAILED(imageUrl 누락·빈 값, 본인이 발급받은 URL 아님)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "AUTHENTICATION_REQUIRED(쿠키 없음, 회원 없음·정지·탈퇴), INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED(USER 권한 없음, CSRF 토큰 없음·불일치)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR")
    @PutMapping("/profile-image")
    public ApiResponse<ProfileImageResponse> registerProfileImage(@Valid @RequestBody ProfileImageUpdateRequest request) {
        return ApiResponse.success(profileImageCommandService.register(
                currentUserIdProvider.currentUserId(), request.imageUrl()));
    }

    @Operation(summary = "내 프로필 이미지 삭제",
            description = "프로필 이미지를 지워 profileImageUrl을 null로 만든다. 이미지가 없어도 성공한다. 이전 이미지는 커밋 후 삭제한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "AUTHENTICATION_REQUIRED(쿠키 없음, 회원 없음·정지·탈퇴), INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED(USER 권한 없음, CSRF 토큰 없음·불일치)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_SERVER_ERROR")
    @DeleteMapping("/profile-image")
    public ResponseEntity<Void> removeProfileImage() {
        profileImageCommandService.remove(currentUserIdProvider.currentUserId());
        return ResponseEntity.noContent().build();
    }

    // JwtAuthenticationFilter가 AuthRole.authority()로 만든 권한만 되돌린다. 그 외 권한은 roles에 넣지 않는다
    private static Set<AuthRole> rolesOf(Authentication authentication) {
        return authentication.getAuthorities().stream() // 인증 객체의 권한들 순회 -> 각 항목은 GrantedAuthority 객체
                .map(GrantedAuthority::getAuthority) // GrantedAuthority에서 "ROLE_USER"와 같은 문자열 꺼냄
                .map(AuthRole::fromAuthority) // ROLE_접두사 없애기
                .flatMap(Optional::stream) // 값이 있는 Optional에서는 enum을 꺼내고, 빈 Optional은 제외하고 flatMap이 이를 하나로 합침
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AuthRole.class))); // EnumSet 사용하여 중복 없는 권한들을 담음
    }
}
