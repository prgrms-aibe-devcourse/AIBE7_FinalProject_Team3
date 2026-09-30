package org.example.grab.global.security.jwt;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.InvalidAccessTokenException.Reason;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/*
    요청마다 access_token 쿠키의 JWT를 검증해 SecurityContext에 인증 정보를 넣는다.
    - Authorization 헤더는 읽지 않는다(M00-07).
    - 쿠키가 없으면 인증 없이 다음 필터로 넘긴다. 보호 경로라면 이후 인가 단계에서 401 AUTHENTICATION_REQUIRED가 된다.
    - 검증에 실패하면 SecurityContext를 비우고 AuthenticationEntryPoint로 401 INVALID_TOKEN을 응답한다.
      예외를 삼키고 익명으로 통과시키지 않는다.
    - DB를 조회하지 않는다. 토큰의 값만으로 인증 정보를 만든다.

    @Component로 등록하지 않는다. 서블릿 필터로도 자동 등록돼 요청마다 두 번 실행되므로
    SecurityConfig에서 생성해 보안 필터 체인에만 넣는다(M03-06).
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String ACCESS_TOKEN_COOKIE = "access_token";
    // 로그인·회원가입·재발급·로그아웃은 무효 토큰을 가진 채로도 호출할 수 있어야 함
    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";

    private final JwtProvider jwtProvider;
    // 인증되지 않았거나 인증에 실패한 요청에 대한 어떤 응답을 보낼지 처리하는 Spring Security 인터페이스
    private final AuthenticationEntryPoint authenticationEntryPoint;

    @Override
    // 로그인, 재발급 요청이 기존 Access Token의 만료 때문에 막히지 않도록 /api/v1/auth 로 시작하는 경로에서 JWT 필터를 생략
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(AUTH_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 요청 사용자의 access_token 의 값을 불러옴
        Cookie cookie = WebUtils.getCookie(request, ACCESS_TOKEN_COOKIE);
        if (cookie == null) {
            chain.doFilter(request, response);
            return;
        }
        // Authentication: Spring Security에서 현재 요청의 사용자가 누구이고, 어떤 권한을 가졌는지 표현하는 객체
        Authentication authentication;
        try {
            // JWT 검증 후 사용자 정보를 Authentication 객체로 변환
            authentication = toAuthentication(jwtProvider.parse(cookie.getValue()));
        } catch (InvalidAccessTokenException e) {
            // 토큰 원문·예외 메시지는 남기지 않고 사유만 남긴다
            log.warn("Access Token 거부: reason={}", e.getReason());
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response, e);
            return;
        }
        // 검증 성공했으므로 Spring Security 인증 정보로 연결시킴
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // 필터의 다음 단계로 넘김
        chain.doFilter(request, response);
    }

    // 서명·Claim 검증은 통과했지만 sub·roles가 우리가 발급한 형식이 아니면 거부한다
    private static Authentication toAuthentication(Claims claims) {
        AccessTokenPrincipal principal = new AccessTokenPrincipal(publicIdOf(claims));
        List<SimpleGrantedAuthority> authorities = rolesOf(claims).stream()
                // AuthRole의 authority 메서드를 사용해서 "ROLE_USER" 와 같은 형식으로 변환
                .map(role -> new SimpleGrantedAuthority(role.authority()))
                .toList();
        // 사용자 정보와 권한을 담은 인증 완료 객체를 만들어 반환
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    // claims의 sub(public_id) 추출
    private static UUID publicIdOf(Claims claims) {
        try {
            return UUID.fromString(claims.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidAccessTokenException(Reason.CLAIM);
        }
    }

    // roles Claim을 읽고 검증해서 List<AuthRole>로 반환
    private static List<AuthRole> rolesOf(Claims claims) {
        // claims의 roles가 공백이거나 리스트 형식이 아닐 경우 에러 던짐
        if (!(claims.get(JwtProvider.ROLES_CLAIM) instanceof List<?> names) || names.isEmpty()) {
            throw new InvalidAccessTokenException(Reason.CLAIM);
        }
        try {
            return names.stream()
                    .map(name -> AuthRole.valueOf((String) name))
                    .toList();
            // enum에 없는 이름이거나 문자열이 아닌 요소거나 목록 안에 null이 있을 경우 예외 던짐
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            throw new InvalidAccessTokenException(Reason.CLAIM);
        }
    }
}
