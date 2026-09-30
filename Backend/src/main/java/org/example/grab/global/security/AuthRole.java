package org.example.grab.global.security;

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

    public String authority() {
        return "ROLE_" + name();
    }
}
