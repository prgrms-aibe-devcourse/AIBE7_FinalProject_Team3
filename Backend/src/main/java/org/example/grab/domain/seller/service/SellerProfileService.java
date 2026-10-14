package org.example.grab.domain.seller.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.seller.repository.SellerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/*
    판매자 프로필 조회. 다른 도메인이 sellers의 표시용 값을 필요로 할 때 repository/entity를 직접 참조하지 않고
    이 service를 거친다(공개 DROP 상세의 브랜드명 등). 인증 경로의 SellerIdLookupService와 역할을 분리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerProfileService {

    private final SellerRepository sellerRepository;

    // 판매자가 없으면 empty. 호출자가 상황에 맞는 오류로 변환한다.
    public Optional<String> findBrandName(Long sellerId) {
        return sellerRepository.findBrandNameById(sellerId);
    }
}
