package org.example.grab.global.security;

import lombok.RequiredArgsConstructor;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecurityContextCurrentSellerIdProvider implements CurrentSellerIdProvider {

    private final SellerIdResolver sellerIdResolver;

    // ROLE_SELLER를 확인한 뒤 principal의 public_id로 승인된 판매자의 sellers.id를 조회한다
    @Override
    public Long currentSellerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
        }
        boolean seller = authentication.getAuthorities().stream()
                .anyMatch(authority -> AuthRole.SELLER.authority().equals(authority.getAuthority()));
        if (!seller) {
            throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
        }
        if (authentication.getPrincipal() instanceof AuthenticatedUser authenticatedUser) {
            // 토큰의 SELLER는 발급 시점 값이다. 그 뒤 승인이 취소됐으면 조회 결과가 없어 바로 막힌다
            return sellerIdResolver.resolveApproved(authenticatedUser.publicId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.ACCESS_DENIED));
        }
        throw new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
