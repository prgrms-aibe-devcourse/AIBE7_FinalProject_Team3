package org.example.grab.domain.auth.support;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.user.entity.UserRole;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.identity.SellerIdResolver;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/*
    발급 시점의 유효 권한(roles)을 계산한다(COMMON 2.1, GR-34 M00-05). 로그인 응답의 roles와 JWT roles Claim이 같은 값을 쓴다.
    - USER: 모든 활성 회원의 기본 권한. ADMIN 회원도 받는다
    - ADMIN: users.role = ADMIN
    - SELLER: sellers.status = APPROVED. users.role에 저장하지 않고 매번 조회한다
    활성 상태는 호출 전에 확인한다(로그인은 UserCredentialService). 재발급(GR-35)도 회원 상태·역할을 다시 읽은 뒤 이 계산을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class AuthRoleCalculator {

    private final SellerIdResolver sellerIdResolver;

    // EnumSet은 AuthRole 선언 순서(USER, SELLER, ADMIN)로 순회하므로 응답 roles의 순서가 JWT Claim(JwtProvider 정렬)과 같다
    public Set<AuthRole> calculate(UUID publicId, UserRole userRole) {
        Objects.requireNonNull(publicId, "publicId");
        Objects.requireNonNull(userRole, "userRole");

        Set<AuthRole> roles = EnumSet.of(AuthRole.USER);
        if (userRole == UserRole.ADMIN) {
            roles.add(AuthRole.ADMIN);
        }
        if (sellerIdResolver.resolveApproved(publicId).isPresent()) {
            roles.add(AuthRole.SELLER);
        }
        return Collections.unmodifiableSet(roles);
    }
}
