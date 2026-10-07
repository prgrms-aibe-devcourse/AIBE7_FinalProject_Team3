package org.example.grab.domain.user.controller;

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
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/email-verification")
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    /*
        IP는 request.getRemoteAddr()로만 구한다. 운영의 Nginx 뒤에서는 Tomcat이 내부 프록시의 X-Forwarded-For로 바꿔 주고,
        외부에서 직접 보낸 헤더는 무시한다(M00-04). 헤더를 직접 읽으면 IP 한도를 위조로 피할 수 있다.
     */
    // 인증 코드 발송 요청
    @PostMapping
    public ResponseEntity<Void> requestCode(@Valid @RequestBody EmailVerificationRequest request,
                                            HttpServletRequest httpRequest) {
        emailVerificationService.requestCode(request.email(), httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    // 인증 코드 확인 및 가입 토큰 쿠키 전달
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmCode(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        String signupToken = emailVerificationService.confirmCode(request.email(), request.code());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, EmailSignupTokenCookie.issue(signupToken).toString())
                .build();
    }
}
