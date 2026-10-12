package org.example.grab.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.user.dto.request.EmailVerificationConfirmRequest;
import org.example.grab.domain.user.dto.request.EmailVerificationRequest;
import org.example.grab.domain.user.service.EmailVerificationService;
import org.example.grab.domain.user.support.EmailSignupTokenCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
    이메일 인증 코드 요청·확인(MEMBER_AUTH 1.2.1, 1.2.2). 명세가 204 No Content라 ApiResponse로 감싸지 않고 본문 없이 반환한다.
    가입 컨텍스트 토큰은 응답 본문에 넣지 않고 HttpOnly 쿠키로만 전달한다(NFR-011).
    Swagger에는 요약과 오류 코드만 적는다. 세부 규칙·수치는 명세(MEMBER_AUTH.md)를 기준으로 하고 여기에 옮겨 적지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/email-verification")
@Tag(name = "이메일 인증", description = "LOCAL 회원가입용 이메일 인증 코드 요청·확인")
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    /*
        IP는 request.getRemoteAddr()로만 구한다. 운영의 Nginx 뒤에서는 Tomcat이 내부 프록시의 X-Forwarded-For로 바꿔 주고,
        외부에서 직접 보낸 헤더는 무시한다(M00-04). 헤더를 직접 읽으면 IP 한도를 위조로 피할 수 있다.
     */
    @Operation(summary = "이메일 인증 코드 요청",
            description = "가입 여부와 관계없이 같은 응답이며, 메일 발송 완료를 기다리지 않는다. 발송 제한은 MEMBER_AUTH.md 1.2 참고")
    @ApiResponse(responseCode = "204", description = "요청 처리 완료")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED, INVALID_EMAIL")
    @ApiResponse(responseCode = "429", description = "EMAIL_VERIFICATION_RESEND_TOO_SOON")
    @PostMapping
    public ResponseEntity<Void> requestCode(@Valid @RequestBody EmailVerificationRequest request,
                                            HttpServletRequest httpRequest) {
        emailVerificationService.requestCode(request.email(), httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "이메일 인증 코드 확인",
            description = "성공하면 15분간 유효한 가입 토큰을 email_signup_token HttpOnly 쿠키로 발급한다. 코드 형식 오류는 시도 횟수에 포함하지 않는다")
    @ApiResponse(responseCode = "204", description = "확인 성공, Set-Cookie로 가입 토큰 발급")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED, INVALID_EMAIL, EMAIL_VERIFICATION_CODE_MISMATCH, EMAIL_VERIFICATION_CODE_EXPIRED")
    @ApiResponse(responseCode = "409", description = "DUPLICATE_EMAIL")
    @ApiResponse(responseCode = "429", description = "EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED")
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmCode(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        String signupToken = emailVerificationService.confirmCode(request.email(), request.code());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, EmailSignupTokenCookie.issue(signupToken).toString())
                .build();
    }
}
