package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.order.dto.PaymentExpiryCandidate;
import org.example.grab.domain.order.dto.PaymentExpiryResult;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/*
    결제 대기 만료 배치의 한 번 실행(GR-65, ERD.md 3.2). 이 클래스는 트랜잭션을 열지 않는다.
    후보는 잠그지 않고 키셋으로 끝까지 읽고, 주문마다 OrderPaymentService의 별도 트랜잭션에서 SKIP LOCKED로 만료한다.
    한 주문의 실패가 나머지 주문의 만료를 막지 않도록 주문 단위로 예외를 격리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentExpiryService {

    static final int BATCH_SIZE = 500;

    private final OrderPaymentService orderPaymentService;

    /** 마감이 지난 결제 대기 주문을 모두 만료한다. 한 실행의 모든 페이지가 같은 now를 쓴다. 만료한 주문 수를 반환한다. */
    public int expire(OffsetDateTime now) {
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        PaymentExpiryCandidate after = null;
        while (true) {
            List<PaymentExpiryCandidate> candidates = orderPaymentService.findPaymentExpiryCandidates(now, after, BATCH_SIZE);
            for (PaymentExpiryCandidate candidate : candidates) {
                PaymentExpiryResult result = expireOne(candidate.getId(), now);
                if (result == PaymentExpiryResult.EXPIRED) {
                    expired++;
                } else if (result == PaymentExpiryResult.SKIPPED_LOCKED) {
                    skipped++;
                } else if (result == null) {
                    failed++;
                }
            }
            // 잠겨 건너뛴 주문도 커서는 넘어간다. 같은 실행에서 다시 읽지 않고 다음 실행에서 조회된다.
            if (candidates.size() < BATCH_SIZE) {
                break;
            }
            after = candidates.get(candidates.size() - 1);
        }
        if (expired + skipped + failed > 0) {
            log.info("결제 대기 만료: 만료 {}건, 잠금으로 건너뜀 {}건, 실패 {}건", expired, skipped, failed);
        }
        return expired;
    }

    // 실패하면 null을 돌려준다. 실패한 주문은 다음 실행에서 다시 조회된다.
    private PaymentExpiryResult expireOne(Long orderId, OffsetDateTime now) {
        try {
            return orderPaymentService.expireIfDueSkippingLocked(orderId, now);
        } catch (Exception e) {
            log.error("결제 대기 만료 실패: orderId={}", orderId, e);
            return null;
        }
    }
}
