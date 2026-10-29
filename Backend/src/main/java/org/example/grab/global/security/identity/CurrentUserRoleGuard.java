package org.example.grab.global.security.identity;

public interface CurrentUserRoleGuard {

    /** 운영자(ADMIN) 계정이면 ADMIN_ACTION_NOT_ALLOWED를 던진다. WISH·주문 생성처럼 구매자 행동에서만 호출한다. */
    void rejectIfAdmin();
}
