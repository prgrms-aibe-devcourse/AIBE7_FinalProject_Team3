package org.example.grab.domain.seller.service;

import org.example.grab.domain.seller.dto.AdminSellerApplicationResponse;
import org.example.grab.domain.seller.dto.SellerApplicationApprovalResponse;
import org.example.grab.domain.seller.dto.SellerApplicationRejectionResponse;
import org.example.grab.domain.seller.entity.Seller;
import org.example.grab.domain.seller.entity.SellerStatus;
import org.example.grab.domain.seller.error.SellerErrorCode;
import org.example.grab.domain.seller.repository.SellerRepository;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

// 운영자 판매자 신청 심사(GR-92, SELLER-002). 상태 전환 자체는 Seller.approve/reject가 담당한다.
@Service
public class AdminSellerApplicationService {

    private final SellerRepository sellerRepository;
    private final Clock clock;

    @Autowired
    public AdminSellerApplicationService(SellerRepository sellerRepository) {
        this(sellerRepository, Clock.systemUTC());
    }

    AdminSellerApplicationService(SellerRepository sellerRepository, Clock clock) {
        this.sellerRepository = sellerRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminSellerApplicationResponse> findApplications(SellerStatus status, int page, int size) {
        return PageResponse.from(sellerRepository.findApplications(status, PageRequest.of(page, size)));
    }

    @Transactional
    public SellerApplicationApprovalResponse approve(UUID applicationId, Long adminId) {
        Seller seller = findByApplicationId(applicationId);
        seller.approve(adminId, OffsetDateTime.now(clock));
        return new SellerApplicationApprovalResponse(seller.getUuid(), seller.getStatus(), seller.getReviewedAt());
    }

    @Transactional
    public SellerApplicationRejectionResponse reject(UUID applicationId, Long adminId, String reason) {
        Seller seller = findByApplicationId(applicationId);
        seller.reject(adminId, OffsetDateTime.now(clock), reason);
        return new SellerApplicationRejectionResponse(seller.getUuid(), seller.getStatus(), seller.getRejectionReason(),
                seller.getReviewedAt());
    }

    private Seller findByApplicationId(UUID applicationId) {
        return sellerRepository.findByUuidForUpdate(applicationId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));
    }
}
