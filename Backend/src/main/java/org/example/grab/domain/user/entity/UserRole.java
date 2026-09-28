package org.example.grab.domain.user.entity;

// SELLER는 sellers.status = APPROVED에서 파생하므로 회원 역할로 두지 않는다.
public enum UserRole {
    USER,
    ADMIN
}
