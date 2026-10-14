package org.example.grab.global.security;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
    Access Token의 roles Claim에 담는 권한. JWT에는 접두사 없이 name()을 넣고,
    Spring Security 권한으로 바꿀 때 ROLE_ 접두사를 붙인다.
    USER·ADMIN은 users.role에서, SELLER는 sellers.status = APPROVED에서 발급 시점에 계산한다(GR-34).
    domain의 UserRole에는 SELLER가 없고 global은 domain을 참조하지 않으므로 별도로 둔다.
 */
public enum AuthRole {
    USER,
    SELLER,
    ADMIN;

    // authority()의 역방향 조회용. 접두사 규칙을 authority() 한 곳에서만 정하도록 그 결과로 만든다
    private static final Map<String, AuthRole> BY_AUTHORITY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    AuthRole::authority, // 키 : 각 enum의 authority() 결과
                    Function.identity()) // 값 : 해당 enum 자체
            );

    public String authority() {
        return "ROLE_" + name();
    }

    // Spring Security 권한 문자열(ROLE_USER 등)을 되돌린다. 이 enum이 만든 권한이 아니면 빈 결과다(GR-97 M01-02)
    public static Optional<AuthRole> fromAuthority(String authority) {
        // 불변 Map은 null 키 조회에 NPE를 던진다. GrantedAuthority.getAuthority()는 null일 수 있다
        if (authority == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_AUTHORITY.get(authority));
    }
}
