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
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class AdminSellerApplicationServiceTest {

    private static final Long ADMIN_ID = 100L;
    private static final String BRN = "1234567890";

    private SellerRepository sellerRepository;
    private AdminSellerApplicationService service;

    @BeforeEach
    void setUp() {
        sellerRepository = mock(SellerRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);
        service = new AdminSellerApplicationService(sellerRepository, clock);
    }

    @Test
    @DisplayName("목록 조회는 레포지토리 결과를 페이지 응답으로 그대로 감싼다")
    void findApplicationsDelegatesToRepository() {
        AdminSellerApplicationResponse item = new AdminSellerApplicationResponse(
                UUID.randomUUID(), UUID.randomUUID(), "길동상점", "seller@example.com", BRN,
                SellerStatus.PENDING, now());
        Page<AdminSellerApplicationResponse> page = new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1);
        given(sellerRepository.findApplications(eq(SellerStatus.PENDING), any())).willReturn(page);

        PageResponse<AdminSellerApplicationResponse> response = service.findApplications(SellerStatus.PENDING, 0, 20);

        assertThat(response.content()).containsExactly(item);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("승인하면 상태가 APPROVED로 바뀌고 심사자·시각이 남는다")
    void approvesApplication() {
        Seller seller = Seller.apply(1L, "길동상점", "seller@example.com", BRN, null, now());
        UUID applicationId = seller.getUuid();
        given(sellerRepository.findByUuidForUpdate(applicationId)).willReturn(Optional.of(seller));

        SellerApplicationApprovalResponse response = service.approve(applicationId, ADMIN_ID);

        assertThat(response.applicationId()).isEqualTo(applicationId);
        assertThat(response.status()).isEqualTo(SellerStatus.APPROVED);
        assertThat(seller.getReviewedBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("존재하지 않는 신청을 승인하려 하면 404로 거부한다")
    void approveNotFound() {
        UUID applicationId = UUID.randomUUID();
        given(sellerRepository.findByUuidForUpdate(applicationId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(applicationId, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("PENDING이 아닌 신청을 승인하려 하면 409로 거부한다")
    void approveRejectsInvalidStateTransition() {
        Seller seller = Seller.apply(1L, "길동상점", "seller@example.com", BRN, null, now());
        seller.approve(ADMIN_ID, now());
        UUID applicationId = seller.getUuid();
        given(sellerRepository.findByUuidForUpdate(applicationId)).willReturn(Optional.of(seller));

        assertThatThrownBy(() -> service.approve(applicationId, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("반려하면 상태가 REJECTED로 바뀌고 사유가 남는다")
    void rejectsApplication() {
        Seller seller = Seller.apply(1L, "길동상점", "seller@example.com", BRN, null, now());
        UUID applicationId = seller.getUuid();
        given(sellerRepository.findByUuidForUpdate(applicationId)).willReturn(Optional.of(seller));

        SellerApplicationRejectionResponse response = service.reject(applicationId, ADMIN_ID, "서류 미비");

        assertThat(response.status()).isEqualTo(SellerStatus.REJECTED);
        assertThat(response.rejectedReason()).isEqualTo("서류 미비");
    }

    @Test
    @DisplayName("존재하지 않는 신청을 반려하려 하면 404로 거부한다")
    void rejectNotFound() {
        UUID applicationId = UUID.randomUUID();
        given(sellerRepository.findByUuidForUpdate(applicationId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reject(applicationId, ADMIN_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("PENDING이 아닌 신청을 반려하려 하면 409로 거부한다")
    void rejectRejectsInvalidStateTransition() {
        Seller seller = Seller.apply(1L, "길동상점", "seller@example.com", BRN, null, now());
        seller.reject(ADMIN_ID, now(), "최초 반려 사유");
        UUID applicationId = seller.getUuid();
        given(sellerRepository.findByUuidForUpdate(applicationId)).willReturn(Optional.of(seller));

        assertThatThrownBy(() -> service.reject(applicationId, ADMIN_ID, "재반려 사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
