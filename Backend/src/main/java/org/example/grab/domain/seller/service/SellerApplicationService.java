package org.example.grab.domain.seller.service;

import org.example.grab.domain.seller.dto.MySellerApplicationResponse;
import org.example.grab.domain.seller.dto.SellerApplicationRequest;
import org.example.grab.domain.seller.dto.SellerApplicationResponse;
import org.example.grab.domain.seller.entity.DocumentType;
import org.example.grab.domain.seller.entity.Seller;
import org.example.grab.domain.seller.entity.SellerDocument;
import org.example.grab.domain.seller.entity.SellerStatus;
import org.example.grab.domain.seller.error.SellerErrorCode;
import org.example.grab.domain.seller.gateway.BusinessRegistrationStatus;
import org.example.grab.domain.seller.gateway.BusinessRegistrationVerifier;
import org.example.grab.domain.seller.repository.SellerDocumentRepository;
import org.example.grab.domain.seller.repository.SellerRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// 판매자 등록 신청·재신청·내 신청 조회(GR-91, SELLER-001·SELLER-005·SELLER-006).
@Service
public class SellerApplicationService {

    private final SellerRepository sellerRepository;
    private final SellerDocumentRepository sellerDocumentRepository;
    private final BusinessRegistrationVerifier businessRegistrationVerifier;
    private final Clock clock;

    @Autowired
    public SellerApplicationService(SellerRepository sellerRepository,
                                    SellerDocumentRepository sellerDocumentRepository,
                                    BusinessRegistrationVerifier businessRegistrationVerifier) {
        this(sellerRepository, sellerDocumentRepository, businessRegistrationVerifier, Clock.systemUTC());
    }

    SellerApplicationService(SellerRepository sellerRepository,
                             SellerDocumentRepository sellerDocumentRepository,
                             BusinessRegistrationVerifier businessRegistrationVerifier,
                             Clock clock) {
        this.sellerRepository = sellerRepository;
        this.sellerDocumentRepository = sellerDocumentRepository;
        this.businessRegistrationVerifier = businessRegistrationVerifier;
        this.clock = clock;
    }

    @Transactional
    public SellerApplicationResponse apply(Long userId, SellerApplicationRequest request) {
        requireAllDocumentTypes(request.documentIds());

        Optional<Seller> existing = sellerRepository.findByUserId(userId);
        if (existing.isPresent() && existing.get().getStatus() != SellerStatus.REJECTED) {
            throw new BusinessException(SellerErrorCode.SELLER_APPLICATION_ALREADY_EXISTS);
        }

        sellerRepository.findByBusinessRegistrationNumber(request.businessRegistrationNumber())
                .filter(seller -> !seller.getUserId().equals(userId))
                .ifPresent(seller -> {
                    throw new BusinessException(SellerErrorCode.DUPLICATE_BUSINESS_REGISTRATION_NUMBER);
                });

        verifyBusinessRegistration(request.businessRegistrationNumber());

        OffsetDateTime now = OffsetDateTime.now(clock);
        Seller seller = existing
                .map(existingSeller -> {
                    existingSeller.reapply(request.brandName(), request.contactEmail(),
                            request.businessRegistrationNumber(), request.description(), now);
                    return existingSeller;
                })
                .orElseGet(() -> sellerRepository.save(Seller.apply(userId, request.brandName(),
                        request.contactEmail(), request.businessRegistrationNumber(), request.description(), now)));

        attachDocuments(userId, seller.getId(), request.documentIds());

        return new SellerApplicationResponse(seller.getUuid(), seller.getBrandName(), seller.getStatus(), seller.getSubmittedAt());
    }

    @Transactional(readOnly = true)
    public MySellerApplicationResponse findMyApplication(Long userId) {
        Seller seller = sellerRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));
        return new MySellerApplicationResponse(seller.getUuid(), seller.getBrandName(), seller.getContactEmail(),
                seller.getBusinessRegistrationNumber(), seller.getDescription(), seller.getStatus(), seller.getSubmittedAt());
    }

    private void verifyBusinessRegistration(String businessRegistrationNumber) {
        BusinessRegistrationStatus status = businessRegistrationVerifier.verify(businessRegistrationNumber);
        // UNAVAILABLE(외부 조회 실패)은 검증을 생략하고 PENDING으로 접수한다(SELLER-005)
        if (status == BusinessRegistrationStatus.INACTIVE) {
            throw new BusinessException(SellerErrorCode.BUSINESS_NOT_ACTIVE);
        }
    }

    private void requireAllDocumentTypes(Map<DocumentType, UUID> documentIds) {
        for (DocumentType type : DocumentType.values()) {
            if (documentIds.get(type) == null) {
                throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
            }
        }
    }

    // 본인이 업로드했고 다른 신청에 연결되지 않은 서류만 붙인다(SELLER-006, 도용 방지)
    private void attachDocuments(Long userId, Long sellerId, Map<DocumentType, UUID> documentIds) {
        List<UUID> ids = new ArrayList<>(documentIds.values());
        List<SellerDocument> documents = sellerDocumentRepository.findAllByIdIn(ids);
        if (documents.size() != ids.size() || documents.stream().anyMatch(document -> !document.isUsableBy(userId, sellerId))) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        documents.forEach(document -> document.attachToSeller(sellerId));
    }
}
