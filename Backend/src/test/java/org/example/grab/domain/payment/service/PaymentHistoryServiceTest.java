package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.dto.PayableOrder;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.dto.PaymentHistoryResponse;
import org.example.grab.domain.payment.dto.SellerPaymentHistoryResponse;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.entity.ReconciliationStatus;
import org.example.grab.domain.payment.repository.PaymentRepository;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentHistoryServiceTest {

    private static final long BUYER_ID = 1L;
    private static final long SELLER_ID = 7L;
    private static final long ORDER_ID = 10L;
    private static final UUID ORDER_UUID = UUID.randomUUID();
    private static final OffsetDateTime REQUESTED_AT = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-10-15T10:01:00+09:00");
    private static final OffsetDateTime CANCELED_AT = OffsetDateTime.parse("2026-10-15T11:00:00+09:00");

    private final OrderPaymentService orderPaymentService = mock(OrderPaymentService.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentHistoryService paymentHistoryService =
            new PaymentHistoryService(orderPaymentService, paymentRepository);

    @Test
    @DisplayName("구매자 이력은 시도 순서대로 시도 시각·승인 시각과 실패 사유를 반환한다")
    void findsBuyerHistory() {
        // given
        Payment failed = payment("payment-key-1");
        failed.fail("REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다.");
        Payment succeeded = payment("payment-key-2");
        succeeded.succeed(APPROVED_AT);
        given(orderPaymentService.findForBuyerPaymentHistory(BUYER_ID, ORDER_UUID)).willReturn(order());
        given(paymentRepository.findByOrderIdOrderByIdAsc(ORDER_ID)).willReturn(List.of(failed, succeeded));

        // when
        List<PaymentHistoryResponse> result = paymentHistoryService.findBuyerHistory(BUYER_ID, ORDER_UUID);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).satisfies(history -> {
            assertThat(history.paymentId()).isEqualTo(failed.getUuid());
            assertThat(history.orderId()).isEqualTo(ORDER_UUID);
            assertThat(history.provider()).isEqualTo(PaymentProvider.TOSS);
            assertThat(history.amount()).isEqualTo(33000);
            assertThat(history.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(history.reconciliationStatus()).isEqualTo(ReconciliationStatus.NONE);
            assertThat(history.requestedAt()).isEqualTo(REQUESTED_AT);
            assertThat(history.paidAt()).isNull();
            assertThat(history.canceledAt()).isNull();
            assertThat(history.failure().code()).isEqualTo("REJECT_CARD_PAYMENT");
            assertThat(history.failure().message()).isEqualTo("카드 승인이 거절되었습니다.");
        });
        assertThat(result.get(1)).satisfies(history -> {
            assertThat(history.paymentId()).isEqualTo(succeeded.getUuid());
            assertThat(history.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(history.paidAt()).isEqualTo(APPROVED_AT);
            assertThat(history.failure()).isNull();
        });
    }

    @Test
    @DisplayName("취소된 결제는 승인 시각과 취소 시각을 함께 반환한다")
    void findsCanceledPayment() {
        // given
        Payment canceled = payment("payment-key");
        canceled.succeed(APPROVED_AT);
        canceled.cancel(CANCELED_AT);
        given(orderPaymentService.findForBuyerPaymentHistory(BUYER_ID, ORDER_UUID)).willReturn(order());
        given(paymentRepository.findByOrderIdOrderByIdAsc(ORDER_ID)).willReturn(List.of(canceled));

        // when
        List<PaymentHistoryResponse> result = paymentHistoryService.findBuyerHistory(BUYER_ID, ORDER_UUID);

        // then
        assertThat(result).singleElement().satisfies(history -> {
            assertThat(history.status()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(history.paidAt()).isEqualTo(APPROVED_AT);
            assertThat(history.canceledAt()).isEqualTo(CANCELED_AT);
        });
    }

    @Test
    @DisplayName("결제 시도가 없으면 빈 목록을 반환한다")
    void returnsEmptyHistory() {
        // given
        given(orderPaymentService.findForBuyerPaymentHistory(BUYER_ID, ORDER_UUID)).willReturn(order());
        given(paymentRepository.findByOrderIdOrderByIdAsc(ORDER_ID)).willReturn(List.of());

        // when
        List<PaymentHistoryResponse> result = paymentHistoryService.findBuyerHistory(BUYER_ID, ORDER_UUID);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("다른 구매자의 주문이면 결제를 조회하지 않고 ORDER_NOT_FOUND로 거부한다")
    void rejectsOtherBuyersOrder() {
        // given
        given(orderPaymentService.findForBuyerPaymentHistory(BUYER_ID, ORDER_UUID))
                .willThrow(new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        // when
        Throwable thrown = catchThrowable(() -> paymentHistoryService.findBuyerHistory(BUYER_ID, ORDER_UUID));

        // then
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
        verifyNoInteractions(paymentRepository);
    }

    @Test
    @DisplayName("판매자 이력은 구매자 이력과 같은 순서·시각을 반환한다")
    void findsSellerHistory() {
        // given
        Payment failed = payment("payment-key-1");
        failed.fail("REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다.");
        Payment succeeded = payment("payment-key-2");
        succeeded.succeed(APPROVED_AT);
        given(orderPaymentService.findForSellerPaymentHistory(SELLER_ID, ORDER_UUID)).willReturn(order());
        given(paymentRepository.findByOrderIdOrderByIdAsc(ORDER_ID)).willReturn(List.of(failed, succeeded));

        // when
        List<SellerPaymentHistoryResponse> result = paymentHistoryService.findSellerHistory(SELLER_ID, ORDER_UUID);

        // then
        assertThat(result).extracting(SellerPaymentHistoryResponse::paymentId)
                .containsExactly(failed.getUuid(), succeeded.getUuid());
        assertThat(result.get(0)).satisfies(history -> {
            assertThat(history.orderId()).isEqualTo(ORDER_UUID);
            assertThat(history.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(history.requestedAt()).isEqualTo(REQUESTED_AT);
            assertThat(history.paidAt()).isNull();
        });
        assertThat(result.get(1).paidAt()).isEqualTo(APPROVED_AT);
    }

    @Test
    @DisplayName("본인 DROP의 주문이 아니면 결제를 조회하지 않고 ORDER_ACCESS_DENIED로 거부한다")
    void rejectsOtherSellersOrder() {
        // given
        given(orderPaymentService.findForSellerPaymentHistory(SELLER_ID, ORDER_UUID))
                .willThrow(new BusinessException(OrderErrorCode.ORDER_ACCESS_DENIED));

        // when
        Throwable thrown = catchThrowable(() -> paymentHistoryService.findSellerHistory(SELLER_ID, ORDER_UUID));

        // then
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ACCESS_DENIED));
        verifyNoInteractions(paymentRepository);
    }

    private static PayableOrder order() {
        return new PayableOrder(ORDER_ID, ORDER_UUID, "ORD-20261015-000001", OrderStatus.PAID, 33000, null);
    }

    private static Payment payment(String paymentKey) {
        Payment payment = Payment.request(
                ORDER_ID, PaymentProvider.TOSS, UUID.randomUUID().toString(), "a".repeat(64), paymentKey, 33000);
        ReflectionTestUtils.setField(payment, "createdAt", REQUESTED_AT);
        return payment;
    }
}
