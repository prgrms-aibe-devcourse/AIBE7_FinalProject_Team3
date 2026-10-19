package org.example.grab.domain.payment.service;

import org.example.grab.domain.payment.entity.PaymentCancellationStatus;
import org.example.grab.domain.payment.gateway.PaymentCancelCommand;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CompensationRefundServiceTest {

    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.of(2026, 10, 16, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final String ORDER_NUMBER = "ORD-1";
    private static final PaymentCancelCommand COMMAND =
            new PaymentCancelCommand("payment-key", "주문을 확정하지 못한 결제의 환불", "server-key");
    private static final CompensationRefundTransactionService.RefundTarget TARGET =
            new CompensationRefundTransactionService.RefundTarget(1L, APPROVED_AT, ORDER_NUMBER, COMMAND);

    @Mock
    private CompensationRefundTransactionService transactionService;

    @Mock
    private PaymentGateway paymentGateway;

    private CompensationRefundService refundService;

    @BeforeEach
    void setUp() {
        refundService = new CompensationRefundService(transactionService, paymentGateway);
    }

    @Test
    @DisplayName("PG 결제가 승인 상태이고 주문번호가 같으면 기록의 서버 멱등 키로 전액 취소를 요청하고 결과를 반영한다")
    void cancelsApprovedPayment() {
        // given: 승인 금액이 서버 금액과 달라도 전액 취소한다
        given(paymentGateway.lookup("payment-key"))
                .willReturn(PaymentGatewayResult.approved("DONE", ORDER_NUMBER, 99000L, APPROVED_AT));
        PaymentCancelResult canceled = PaymentCancelResult.canceled("CANCELED", "tx-key", APPROVED_AT);
        given(paymentGateway.cancel(COMMAND)).willReturn(canceled);
        given(transactionService.applyResult(eq(1L), eq(canceled), any()))
                .willReturn(PaymentCancellationStatus.SUCCEEDED);

        // when
        CompensationRefundService.RefundOutcome outcome = refundService.refund(TARGET);

        // then
        assertThat(outcome).isEqualTo(CompensationRefundService.RefundOutcome.SUCCEEDED);
    }

    @Test
    @DisplayName("PG 결제의 주문번호가 서버 주문과 다르면 취소를 요청하지 않고 기록을 주문번호 불일치로 끝낸다")
    void abandonsOnOrderMismatch() {
        // given
        given(paymentGateway.lookup("payment-key"))
                .willReturn(PaymentGatewayResult.approved("DONE", "ORD-OTHER", 33000L, APPROVED_AT));
        given(transactionService.abandonOrderMismatch(eq(1L), any()))
                .willReturn(PaymentCancellationStatus.FAILED);

        // when
        CompensationRefundService.RefundOutcome outcome = refundService.refund(TARGET);

        // then
        assertThat(outcome).isEqualTo(CompensationRefundService.RefundOutcome.ORDER_MISMATCH);
        then(paymentGateway).should(never()).cancel(any());
        then(transactionService).should(never()).applyResult(any(), any(), any());
    }

    @Test
    @DisplayName("PG 결제가 이미 취소 상태여도 같은 서버 멱등 키로 취소를 요청해 취소 시각·거래 키를 받아 반영한다")
    void requestsCancelForAlreadyCanceledPayment() {
        // given: 조회 결과의 취소 상태에는 주문번호·취소 정보가 없다
        given(paymentGateway.lookup("payment-key"))
                .willReturn(PaymentGatewayResult.notApproved("CANCELED", "CANCELED", "결제가 완료되지 않았습니다."));
        PaymentCancelResult canceled = PaymentCancelResult.canceled("CANCELED", "tx-key", APPROVED_AT);
        given(paymentGateway.cancel(COMMAND)).willReturn(canceled);
        given(transactionService.applyResult(eq(1L), eq(canceled), any()))
                .willReturn(PaymentCancellationStatus.SUCCEEDED);

        // when
        CompensationRefundService.RefundOutcome outcome = refundService.refund(TARGET);

        // then
        assertThat(outcome).isEqualTo(CompensationRefundService.RefundOutcome.SUCCEEDED);
    }

    @Test
    @DisplayName("PG 결제 조회에 실패하면 취소를 요청하지 않고 기록을 그대로 둔다")
    void waitsWhenLookupUnknown() {
        // given
        given(paymentGateway.lookup("payment-key"))
                .willReturn(PaymentGatewayResult.unknown(null, null, "결제 상태를 확인하지 못했습니다."));

        // when
        CompensationRefundService.RefundOutcome outcome = refundService.refund(TARGET);

        // then
        assertThat(outcome).isEqualTo(CompensationRefundService.RefundOutcome.UNSETTLED);
        then(paymentGateway).should(never()).cancel(any());
        then(transactionService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("PG 결제가 승인·취소가 아닌 종료 상태면 취소를 요청하지 않는다")
    void waitsWhenNotApprovedOtherwise() {
        // given
        given(paymentGateway.lookup("payment-key"))
                .willReturn(PaymentGatewayResult.notApproved("ABORTED", "ABORTED", "결제가 완료되지 않았습니다."));

        // when
        CompensationRefundService.RefundOutcome outcome = refundService.refund(TARGET);

        // then
        assertThat(outcome).isEqualTo(CompensationRefundService.RefundOutcome.UNSETTLED);
        then(paymentGateway).should(never()).cancel(any());
    }

    @Test
    @DisplayName("PG가 취소를 거절하면 거절로, 결과를 모르면 미확정으로 돌려준다")
    void mapsRejectedAndUnknown() {
        // given
        given(paymentGateway.lookup("payment-key"))
                .willReturn(PaymentGatewayResult.approved("DONE", ORDER_NUMBER, 33000L, APPROVED_AT));
        PaymentCancelResult rejected = PaymentCancelResult.rejected("NOT_CANCELABLE_PAYMENT", "취소할 수 없습니다.");
        PaymentCancelResult unknown = PaymentCancelResult.unknown(null, null, "응답 없음");
        given(paymentGateway.cancel(COMMAND)).willReturn(rejected, unknown);
        given(transactionService.applyResult(eq(1L), eq(rejected), any()))
                .willReturn(PaymentCancellationStatus.FAILED);
        given(transactionService.applyResult(eq(1L), eq(unknown), any()))
                .willReturn(PaymentCancellationStatus.UNKNOWN);

        // when & then
        assertThat(refundService.refund(TARGET)).isEqualTo(CompensationRefundService.RefundOutcome.REJECTED);
        assertThat(refundService.refund(TARGET)).isEqualTo(CompensationRefundService.RefundOutcome.UNSETTLED);
    }
}
