package org.example.grab.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.user.dto.request.SignupRequest;
import org.example.grab.domain.user.dto.response.SignupResponse;
import org.example.grab.domain.user.service.SignupService;
import org.example.grab.domain.user.support.EmailSignupTokenCookie;
import org.example.grab.global.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
    LOCAL 회원가입 완료(MEMBER_AUTH 1.2.3). 로그인 없이 호출하고, 이메일 인증에서 받은 email_signup_token 쿠키가 필요하다.
    응답 본문의 ApiResponse와 이름이 겹쳐 Swagger의 @ApiResponse는 전체 이름으로 쓴다.
    Swagger에는 요약과 오류 코드만 적는다. 세부 규칙은 명세(MEMBER_AUTH.md)를 기준으로 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/signup")
@Tag(name = "회원가입", description = "이메일 인증을 마친 LOCAL 회원가입")
public class SignupController {

    private final SignupService signupService;

    /*
        명세상 요청 본문 검증보다 가입 컨텍스트 확인이 먼저 진행되야 한다(GR-30 M00-01).
        @Valid만 두면 메서드에 들어오기 전에 검증 오류(400)가 나므로,
        BindingResult로 검증 결과를 받아 두고 컨텍스트를 확인한 뒤에 던진다.
        JSON으로 해석할 수 없는 본문은 그 전에 400 INVALID_REQUEST로 처리된다.
        쿠키는 성공했을 때만 지운다. 요청 값 오류·DUPLICATE_NICKNAME 뒤에는 같은 쿠키로 다시 요청할 수 있어야 한다.
     */
    @Operation(summary = "회원가입 완료",
            description = "email_signup_token 쿠키의 인증된 이메일로 가입한다. 성공하면 쿠키를 만료시킨다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "가입 성공, Set-Cookie로 가입 토큰 만료")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED, INVALID_PASSWORD, INVALID_NICKNAME")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "EMAIL_SIGNUP_CONTEXT_INVALID")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_EMAIL, DUPLICATE_NICKNAME")
    @PostMapping
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @CookieValue(name = EmailSignupTokenCookie.NAME, required = false) String signupToken,
            @Valid @RequestBody SignupRequest request,
            BindingResult bindingResult
    ) throws BindException {
        signupService.requireSignupContext(signupToken);
        if (bindingResult.hasErrors()) {
            throw new BindException(bindingResult);
        }

        SignupResponse response = signupService.signup(signupToken, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, EmailSignupTokenCookie.expire().toString())
                .body(ApiResponse.success(response));
    }
}
