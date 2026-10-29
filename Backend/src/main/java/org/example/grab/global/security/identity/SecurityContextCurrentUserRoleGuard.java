package org.example.grab.global.security.identity;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.AuthRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentUserRoleGuard implements CurrentUserRoleGuard {

    // ADMIN도 토큰에 ROLE_USER를 같이 받아(AuthRoleCalculator) WISH·주문 생성 API를 호출할 수 있다.
    // 운영자가 한정 수량 DROP을 선점하지 못하도록 여기서 따로 막는다.
    @Override
    public void rejectIfAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> AuthRole.ADMIN.authority().equals(authority.getAuthority()));
        if (admin) {
            throw new BusinessException(CommonErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        }
    }
}
