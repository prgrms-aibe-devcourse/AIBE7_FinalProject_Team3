package org.example.grab.global.security.identity;

import lombok.RequiredArgsConstructor;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.AuthenticatedUser;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecurityContextCurrentUserIdProvider implements CurrentUserIdProvider {

    private final UserIdResolver userIdResolver;

    // principal의 public_id를 내부 ID로 바꿔 돌려준다. 컨트롤러·서비스는 계속 Long 내부 ID만 다룬다
    @Override
    public Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (authentication.getPrincipal() instanceof AuthenticatedUser authenticatedUser) {
            // 토큰은 유효하지만 회원 행이 없으면(삭제 등) 인증되지 않은 것으로 본다
            return userIdResolver.resolve(authenticatedUser.publicId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        }
        throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
