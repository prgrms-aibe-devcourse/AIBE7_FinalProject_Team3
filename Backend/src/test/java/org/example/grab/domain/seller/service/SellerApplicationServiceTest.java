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
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;

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
    private Map<DocumentType, UUID> documentIds;

    @BeforeEach
    void setUp() {
        sellerRepository = mock(SellerRepository.class);
        sellerDocumentRepository = mock(SellerDocumentRepository.class);
        businessRegistrationVerifier = mock(BusinessRegistrationVerifier.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);
        service = new SellerApplicationService(sellerRepository, sellerDocumentRepository, businessRegistrationVerifier, clock);
        documentUploaderId = USER_ID;
        documentIds = allDocumentIds();

        given(businessRegistrationVerifier.verify(anyString())).willReturn(BusinessRegistrationStatus.ACTIVE);
        given(sellerRepository.findByBusinessRegistrationNumber(anyString())).willReturn(Optional.empty());
        given(sellerDocumentRepository.findAllByIdIn(any())).willAnswer(invocation -> {
            // any()로 이 메서드를 재스텁하는 테스트가 있는데, 그 재스텁 호출 자체가 이 기존 스텁을
            // null 인자로 한 번 먼저 거쳐가므로 null을 방어한다
            List<UUID> ids = invocation.getArgument(0);
            if (ids == null) {
                return List.of();
            }
            return ids.stream().map(id -> usableDocument(id, documentUploaderId, typeOf(id))).toList();
        });
        given(sellerRepository.saveAndFlush(any(Seller.class))).willAnswer(invocation -> {
            Seller seller = invocation.getArgument(0);
            if (seller.getId() == null) {
                ReflectionTestUtils.setField(seller, "id", 10L);
            }
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
    @DisplayName("사전 검사를 통과해도 저장 시 유니크 제약 위반이면 409로 변환한다(동시 신청)")
    void convertsConstraintViolationOnSaveToDuplicateBusinessNumber() {
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(sellerRepository.saveAndFlush(any(Seller.class)))
                .willThrow(violation("uq_sellers_business_registration_number"));

        assertThatThrownBy(() -> service.apply(USER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SellerErrorCode.DUPLICATE_BUSINESS_REGISTRATION_NUMBER);
    }

    @Test
    @DisplayName("저장 시 다른 제약 위반이면 그대로 전파한다")
    void propagatesOtherConstraintViolations() {
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        DataIntegrityViolationException other = violation("uq_sellers_user");
        given(sellerRepository.saveAndFlush(any(Seller.class))).willThrow(other);

        assertThatThrownBy(() -> service.apply(USER_ID, request()))
                .isSameAs(other);
    }

    // Spring이 Hibernate 예외를 감싼 형태(SignupServiceTest와 같은 방식)
    private static DataIntegrityViolationException violation(String constraintName) {
        SQLException sqlException = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + constraintName + "\"", "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sqlException, constraintName));
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
    @DisplayName("documentId의 실제 유형이 요청 키와 다르면 400으로 거부한다(유형 바꿔치기 방지)")
    void rejectsDocumentTypeMismatch() {
        given(sellerRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        // BUSINESS_LICENSE 키에 실제로는 ID_CARD로 업로드된 서류의 id를 끼워 넣는다
        UUID businessLicenseId = documentIds.get(DocumentType.BUSINESS_LICENSE);
        given(sellerDocumentRepository.findAllByIdIn(any())).willAnswer(invocation -> {
            List<UUID> ids = invocation.getArgument(0);
            return ids.stream()
                    .map(id -> usableDocument(id, documentUploaderId,
                            id.equals(businessLicenseId) ? DocumentType.ID_CARD : typeOf(id)))
                    .toList();
        });

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

    private static SellerDocument usableDocument(UUID id, Long uploadedBy, DocumentType documentType) {
        return SellerDocument.upload(id, uploadedBy, documentType, "image/png", "key", "dek");
    }

    // 요청에 쓴 documentIds에서 역으로 유형을 찾는다. 업로드 스텁이 실제 요청과 같은 유형의 서류를 돌려주게 하기 위함
    private DocumentType typeOf(UUID id) {
        return documentIds.entrySet().stream()
                .filter(entry -> entry.getValue().equals(id))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private static Map<DocumentType, UUID> allDocumentIds() {
        return Map.of(
                DocumentType.BUSINESS_LICENSE, UUID.randomUUID(),
                DocumentType.ID_CARD, UUID.randomUUID(),
                DocumentType.BANKBOOK, UUID.randomUUID(),
                DocumentType.MAIL_ORDER_BUSINESS_REPORT, UUID.randomUUID()
        );
    }

    private SellerApplicationRequest request() {
        return request(documentIds);
    }

    private static SellerApplicationRequest request(Map<DocumentType, UUID> documentIds) {
        return new SellerApplicationRequest("길동상점", "seller@example.com", BRN, "한정판 상품 판매점", documentIds);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
