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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SellerApplicationServiceTest {

    private static final Long USER_ID = 1L;
    private static final String BRN = "1234567890";

    private SellerRepository sellerRepository;
    private SellerDocumentRepository sellerDocumentRepository;
    private BusinessRegistrationVerifier businessRegistrationVerifier;
    private SellerApplicationService service;
    private Long documentUploaderId;

    @BeforeEach
    void setUp() {
        sellerRepository = mock(SellerRepository.class);
        sellerDocumentRepository = mock(SellerDocumentRepository.class);
        businessRegistrationVerifier = mock(BusinessRegistrationVerifier.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);
        service = new SellerApplicationService(sellerRepository, sellerDocumentRepository, businessRegistrationVerifier, clock);
        documentUploaderId = USER_ID;

        given(businessRegistrationVerifier.verify(anyString())).willReturn(BusinessRegistrationStatus.ACTIVE);
        given(sellerRepository.findByBusinessRegistrationNumber(anyString())).willReturn(Optional.empty());
        given(sellerDocumentRepository.findAllByIdIn(any())).willAnswer(invocation -> {
            List<UUID> ids = invocation.getArgument(0);
            return ids.stream().map(id -> usableDocument(id, documentUploaderId)).toList();
        });
        given(sellerRepository.save(any(Seller.class))).willAnswer(invocation -> {
            Seller seller = invocation.getArgument(0);
            ReflectionTestUtils.setField(seller, "id", 10L);
            return seller;
        });
    }

    @Test
    @DisplayName("신청 이력이 없으면 새 신청을 PENDING으로 만든다")
    void appliesAsNewPendingApplication() {
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

        SellerApplicationResponse response = service.apply(USER_ID, request());

        assertThat(response.status()).isEqualTo(SellerStatus.PENDING);
        assertThat(response.brandName()).isEqualTo("길동상점");
    }

    @Test
    @DisplayName("PENDING 상태에서 다시 신청하면 409로 거부한다")
    void rejectsReapplyWhilePending() {
        Seller pending = Seller.apply(USER_ID, "기존", "old@example.com", BRN, null, now());
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.apply(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SellerErrorCode.SELLER_APPLICATION_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("REJECTED 상태에서 재신청하면 같은 행을 PENDING으로 되돌린다")
    void reappliesAfterRejection() {
        Seller rejected = Seller.apply(USER_ID, "기존", "old@example.com", BRN, null, now());
        rejected.reject(99L, now(), "서류 미비");
        ReflectionTestUtils.setField(rejected, "id", 5L);
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.of(rejected));

        SellerApplicationResponse response = service.apply(USER_ID, request());

        assertThat(response.status()).isEqualTo(SellerStatus.PENDING);
        assertThat(response.brandName()).isEqualTo("길동상점");
    }

    @Test
    @DisplayName("다른 계정이 같은 사업자등록번호로 이미 신청했으면 409로 거부한다")
    void rejectsDuplicateBusinessRegistrationNumber() {
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        Seller other = Seller.apply(2L, "다른판매자", "other@example.com", BRN, null, now());
        given(sellerRepository.findByBusinessRegistrationNumber(BRN)).willReturn(Optional.of(other));

        assertThatThrownBy(() -> service.apply(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SellerErrorCode.DUPLICATE_BUSINESS_REGISTRATION_NUMBER);
    }

    @Test
    @DisplayName("국세청 조회 결과가 휴폐업이면 422로 거부한다")
    void rejectsInactiveBusiness() {
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(businessRegistrationVerifier.verify(BRN)).willReturn(BusinessRegistrationStatus.INACTIVE);

        assertThatThrownBy(() -> service.apply(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SellerErrorCode.BUSINESS_NOT_ACTIVE);
    }

    @Test
    @DisplayName("국세청 조회가 장애(UNAVAILABLE)면 검증을 생략하고 PENDING으로 접수한다")
    void acceptsWhenVerifierUnavailable() {
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(businessRegistrationVerifier.verify(BRN)).willReturn(BusinessRegistrationStatus.UNAVAILABLE);

        SellerApplicationResponse response = service.apply(USER_ID, request());

        assertThat(response.status()).isEqualTo(SellerStatus.PENDING);
    }

    @Test
    @DisplayName("서류 4종 중 하나라도 누락되면 400으로 거부한다")
    void rejectsMissingDocument() {
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        Map<DocumentType, UUID> incomplete = Map.of(
                DocumentType.BUSINESS_LICENSE, UUID.randomUUID(),
                DocumentType.ID_CARD, UUID.randomUUID(),
                DocumentType.BANKBOOK, UUID.randomUUID()
        );

        assertThatThrownBy(() -> service.apply(USER_ID, request(incomplete)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("본인이 업로드하지 않은 서류를 쓰면 400으로 거부한다(도용 방지)")
    void rejectsDocumentUploadedByAnotherUser() {
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        documentUploaderId = 999L;

        assertThatThrownBy(() -> service.apply(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("내 신청이 없으면 404로 응답한다")
    void findMyApplicationNotFound() {
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findMyApplication(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("내 신청 조회는 본인 신청 정보를 그대로 반환한다")
    void findsMyApplication() {
        Seller seller = Seller.apply(USER_ID, "길동상점", "seller@example.com", BRN, "설명", now());
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.of(seller));

        MySellerApplicationResponse response = service.findMyApplication(USER_ID);

        assertThat(response.brandName()).isEqualTo("길동상점");
        assertThat(response.status()).isEqualTo(SellerStatus.PENDING);
    }

    private static SellerDocument usableDocument(UUID id, Long uploadedBy) {
        return SellerDocument.upload(id, uploadedBy, DocumentType.BUSINESS_LICENSE, "image/png", "key", "dek");
    }

    private static Map<DocumentType, UUID> allDocumentIds() {
        return Map.of(
                DocumentType.BUSINESS_LICENSE, UUID.randomUUID(),
                DocumentType.ID_CARD, UUID.randomUUID(),
                DocumentType.BANKBOOK, UUID.randomUUID(),
                DocumentType.MAIL_ORDER_BUSINESS_REPORT, UUID.randomUUID()
        );
    }

    private static SellerApplicationRequest request() {
        return request(allDocumentIds());
    }

    private static SellerApplicationRequest request(Map<DocumentType, UUID> documentIds) {
        return new SellerApplicationRequest("길동상점", "seller@example.com", BRN, "한정판 상품 판매점", documentIds);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
