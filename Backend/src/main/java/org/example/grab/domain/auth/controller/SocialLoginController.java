package org.example.grab.domain.auth.controller;

import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.global.error.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
    KAKAO·GOOGLE 소셜 로그인(MEMBER_AUTH 1.4, GR-46 M00-02).
    kakao·google 시작 요청은 OAuth2 필터(SocialAuthorizationRequestResolver)가 먼저 처리해 302로 제공자 인증 화면에 보낸다.
    이 Controller에 닿는 provider는 지원하지 않는 값뿐이라 400 UNSUPPORTED_OAUTH2_PROVIDER로 응답한다.
    콜백(GET /{provider}/callback)은 제공자가 호출하며 OAuth2 필터가 처리하므로 여기에 없다(1.5).
 */
@RestController
@RequestMapping("/api/v1/auth/oauth2")
public class SocialLoginController {

    @GetMapping("/{provider}")
    public void start(@PathVariable String provider) {
        throw new BusinessException(UserErrorCode.UNSUPPORTED_OAUTH2_PROVIDER);
    }
}
