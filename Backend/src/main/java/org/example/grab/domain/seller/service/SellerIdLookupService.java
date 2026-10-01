package org.example.grab.domain.seller.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.seller.entity.SellerStatus;
import org.example.grab.domain.seller.repository.SellerRepository;
import org.example.grab.global.security.identity.SellerIdResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/*
    global/security/identity의 SellerIdResolver 구현. 인증된 회원의 public_id로 승인된 판매자의 sellers.id를 찾는다(M00-03).
    승인 취소가 바로 반영되도록 캐시는 두지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerIdLookupService implements SellerIdResolver {

    private final SellerRepository sellerRepository;

    @Override
    public Optional<Long> resolveApproved(UUID userPublicId) {
        return sellerRepository.findIdByUserPublicIdAndStatus(userPublicId, SellerStatus.APPROVED);
    }
}
