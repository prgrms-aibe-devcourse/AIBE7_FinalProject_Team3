package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.dto.PaymentExpiryCandidate;
import org.example.grab.domain.order.dto.PaymentExpiryResult;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentExpiryServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 8, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private OrderPaymentService orderPaymentService;

    @InjectMocks
    private PaymentExpiryService paymentExpiryService;

    @Test
    @DisplayName("페이지가 가득 차면 마지막 후보를 커서로 다음 페이지를 같은 now로 읽고, 덜 차면 멈춘다")
    void readsPagesWithSameNow() {
        // given: 첫 페이지는 BATCH_SIZE건, 둘째 페이지는 1건
        List<PaymentExpiryCandidate> firstPage = candidates(1, PaymentExpiryService.BATCH_SIZE);
        PaymentExpiryCandidate last = firstPage.get(firstPage.size() - 1);
        List<PaymentExpiryCandidate> secondPage = candidates(PaymentExpiryService.BATCH_SIZE + 1, 1);
        given(orderPaymentService.findPaymentExpiryCandidates(eq(NOW), isNull(), eq(PaymentExpiryService.BATCH_SIZE)))
                .willReturn(firstPage);
        given(orderPaymentService.findPaymentExpiryCandidates(NOW, last, PaymentExpiryService.BATCH_SIZE))
                .willReturn(secondPage);
        given(orderPaymentService.expireIfDueSkippingLocked(anyLong(), eq(NOW))).willReturn(PaymentExpiryResult.EXPIRED);

        // when
        int expired = paymentExpiryService.expire(NOW);

        // then
        assertThat(expired).isEqualTo(PaymentExpiryService.BATCH_SIZE + 1);
        then(orderPaymentService).should(never())
                .findPaymentExpiryCandidates(NOW, secondPage.get(0), PaymentExpiryService.BATCH_SIZE);
    }

    @Test
    @DisplayName("한 주문의 만료가 실패해도 나머지 주문을 계속 만료하고, 잠겨 건너뛴 주문은 만료 수에 넣지 않는다")
    void isolatesFailurePerOrder() {
        // given
        given(orderPaymentService.findPaymentExpiryCandidates(any(), isNull(), eq(PaymentExpiryService.BATCH_SIZE)))
                .willReturn(candidates(1, 4));
        given(orderPaymentService.expireIfDueSkippingLocked(1L, NOW)).willThrow(new RuntimeException("일시적 DB 오류"));
        given(orderPaymentService.expireIfDueSkippingLocked(2L, NOW)).willReturn(PaymentExpiryResult.SKIPPED_LOCKED);
        given(orderPaymentService.expireIfDueSkippingLocked(3L, NOW)).willReturn(PaymentExpiryResult.NOT_DUE);
        given(orderPaymentService.expireIfDueSkippingLocked(4L, NOW)).willReturn(PaymentExpiryResult.EXPIRED);

        // when
        int expired = paymentExpiryService.expire(NOW);

        // then
        assertThat(expired).isEqualTo(1);
        then(orderPaymentService).should().expireIfDueSkippingLocked(4L, NOW);
    }

    @Test
    @DisplayName("후보가 없으면 만료를 호출하지 않는다")
    void doesNothingWithoutCandidates() {
        // given
        given(orderPaymentService.findPaymentExpiryCandidates(any(), isNull(), eq(PaymentExpiryService.BATCH_SIZE)))
                .willReturn(List.of());

        // when
        int expired = paymentExpiryService.expire(NOW);

        // then
        assertThat(expired).isZero();
        then(orderPaymentService).should(never()).expireIfDueSkippingLocked(anyLong(), any());
    }

    private static List<PaymentExpiryCandidate> candidates(long firstId, int count) {
        return LongStream.range(firstId, firstId + count)
                .mapToObj(id -> (PaymentExpiryCandidate) new Candidate(id, NOW.toInstant().minusSeconds(60)))
                .toList();
    }

    private record Candidate(Long getId, Instant getPaymentExpiresAt) implements PaymentExpiryCandidate {
    }
}
