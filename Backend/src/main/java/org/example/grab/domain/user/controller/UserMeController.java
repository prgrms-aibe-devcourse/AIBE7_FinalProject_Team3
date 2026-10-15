package org.example.grab.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.user.dto.response.UserMeResponse;
import org.example.grab.domain.user.service.UserMeQueryService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
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
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me")
@Tag(name = "내 정보", description = "로그인한 사용자의 회원 정보 조회")
public class UserMeController {

    private final UserMeQueryService userMeQueryService;
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

    // JwtAuthenticationFilter가 AuthRole.authority()로 만든 권한만 되돌린다. 그 외 권한은 roles에 넣지 않는다
    private static Set<AuthRole> rolesOf(Authentication authentication) {
        return authentication.getAuthorities().stream() // 인증 객체의 권한들 순회 -> 각 항목은 GrantedAuthority 객체
                .map(GrantedAuthority::getAuthority) // GrantedAuthority에서 "ROLE_USER"와 같은 문자열 꺼냄
                .map(AuthRole::fromAuthority) // ROLE_접두사 없애기
                .flatMap(Optional::stream) // 값이 있는 Optional에서는 enum을 꺼내고, 빈 Optional은 제외하고 flatMap이 이를 하나로 합침
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AuthRole.class))); // EnumSet 사용하여 중복 없는 권한들을 담음
    }
}
