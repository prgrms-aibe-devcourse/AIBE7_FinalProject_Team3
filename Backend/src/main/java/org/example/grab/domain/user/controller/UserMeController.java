package org.example.grab.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.user.dto.request.PasswordChangeRequest;
import org.example.grab.domain.user.dto.request.UserNicknameUpdateRequest;
import org.example.grab.domain.user.dto.response.UserMeResponse;
import org.example.grab.domain.user.service.PasswordChangeService;
import org.example.grab.domain.user.service.UserMeCommandService;
import org.example.grab.domain.user.service.UserMeQueryService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me")
@Tag(name = "내 정보", description = "로그인한 사용자의 회원 정보 조회·수정, 비밀번호 변경")
public class UserMeController {

    private final UserMeQueryService userMeQueryService;
    private final UserMeCommandService userMeCommandService;
    private final PasswordChangeService passwordChangeService;
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

    // JwtAuthenticationFilter가 AuthRole.authority()로 만든 권한만 되돌린다. 그 외 권한은 roles에 넣지 않는다
    private static Set<AuthRole> rolesOf(Authentication authentication) {
        return authentication.getAuthorities().stream() // 인증 객체의 권한들 순회 -> 각 항목은 GrantedAuthority 객체
                .map(GrantedAuthority::getAuthority) // GrantedAuthority에서 "ROLE_USER"와 같은 문자열 꺼냄
                .map(AuthRole::fromAuthority) // ROLE_접두사 없애기
                .flatMap(Optional::stream) // 값이 있는 Optional에서는 enum을 꺼내고, 빈 Optional은 제외하고 flatMap이 이를 하나로 합침
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AuthRole.class))); // EnumSet 사용하여 중복 없는 권한들을 담음
    }
}
