package org.example.grab.domain.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.auth.dto.internal.LoginResult;
import org.example.grab.domain.auth.dto.response.LoginResponse;
import org.example.grab.domain.auth.service.SocialLoginService;
import org.example.grab.domain.user.dto.request.OAuth2SignupRequest;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.support.OAuth2SignupTokenCookie;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.security.AuthCookies;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
    KAKAO·GOOGLE 소셜 로그인(MEMBER_AUTH 1.4·1.6, GR-46).
    - 시작(GET /{provider}): kakao·google은 OAuth2 필터(SocialAuthorizationRequestResolver)가 먼저 처리해 302로 제공자 인증 화면에 보낸다.
      이 Controller에 닿는 provider는 지원하지 않는 값뿐이라 400 UNSUPPORTED_OAUTH2_PROVIDER로 응답한다
    - 콜백(GET /{provider}/callback)은 제공자가 호출하며 OAuth2 필터가 처리하므로 여기에 없다(1.5)
    - 가입 완료(POST /signup): oauth2_signup_token 쿠키의 컨텍스트로 회원을 만들고 인증 쿠키를 발급한다. CSRF 토큰이 필요하다
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/oauth2")
public class SocialLoginController {

    private final SocialLoginService socialLoginService;

    @GetMapping("/{provider}")
    public void start(@PathVariable String provider) {
        throw new BusinessException(UserErrorCode.UNSUPPORTED_OAUTH2_PROVIDER);
    }

    /*
        LOCAL 회원가입(SignupController)과 같이 요청 본문 검증보다 가입 컨텍스트 확인을 먼저 한다(1.6).
        @Valid만 두면 메서드에 들어오기 전에 검증 오류(400)가 나므로, BindingResult로 받아 두고 컨텍스트를 확인한 뒤에 던진다.
        JSON으로 해석할 수 없는 본문은 그 전에 400 INVALID_REQUEST로 처리된다.
        가입 쿠키는 성공했을 때만 지운다. 닉네임·요청 값 오류 뒤에는 같은 쿠키로 다시 요청할 수 있어야 한다.
        토큰은 Set-Cookie로만 보내고 본문에는 회원 정보만 담는다(LOCAL 로그인과 같은 응답 형식).
     */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<LoginResponse>> signup(
            @CookieValue(name = OAuth2SignupTokenCookie.NAME, required = false) String signupToken,
            @Valid @RequestBody OAuth2SignupRequest request,
            BindingResult bindingResult
    ) throws BindException {
        // 해당 가입 컨텍스트가 있는지 검증
        socialLoginService.requireSignupContext(signupToken);
        if (bindingResult.hasErrors()) {
            throw new BindException(bindingResult);
        }

        // 가입 컨텍스트 조회 -> 중복 검사 -> 회원 저장 -> 가입 컨텍스트 삭제 -> GRAB 로그인 토큰 발급 -> LoginResult 반환 과정을 거침
        LoginResult result = socialLoginService.signup(signupToken, request.nickname());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, OAuth2SignupTokenCookie.expire().toString()) // 가입 이후 쿠키 만료 시키기
                .header(HttpHeaders.SET_COOKIE, AuthCookies.accessToken(
                        result.accessToken().value(), result.accessToken().maxAge()).toString()) // 가입 이후 access token 및 refresh token 발급
                .header(HttpHeaders.SET_COOKIE, AuthCookies.refreshToken(
                        result.refreshToken(), result.refreshTokenMaxAge()).toString())
                .body(ApiResponse.success(LoginResponse.from(result)));
    }
}
