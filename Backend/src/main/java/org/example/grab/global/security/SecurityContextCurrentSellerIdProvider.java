package org.example.grab.global.security;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentSellerIdProvider implements CurrentSellerIdProvider {

    private static final String SELLER_AUTHORITY = "ROLE_SELLER";

    @Override
    public Long currentSellerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        }
        boolean seller = authentication.getAuthorities().stream()
                .anyMatch(authority -> SELLER_AUTHORITY.equals(authority.getAuthority()));
        if (!seller) {
            throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
        }
        if (authentication.getPrincipal() instanceof AuthenticatedSeller authenticatedSeller) {
            return authenticatedSeller.sellerId();
        }
        throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
