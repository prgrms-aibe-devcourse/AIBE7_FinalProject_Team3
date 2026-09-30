package org.example.grab.domain.payment.service;

import org.example.grab.domain.payment.gateway.toss.TossPaymentsProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/*
    PENDING 정리 기준(PaymentTransactionService.STALE_PENDING_AFTER)은 한 요청이 PENDING을 저장한 뒤
    승인 요청과 결과 불명 시 즉시 조회를 마칠 때까지의 최대 시간보다 길어야 한다.
    짧으면 아직 승인 응답을 기다리는 결제를 다른 요청이 멈춘 결제로 보고 정리하러 들어온다.
    타임아웃 설정만 바꾸는 실수를 배포 시점에 드러내도록 기동할 때 확인한다.
 */
@Component
class PendingPaymentTimeoutCheck {

    PendingPaymentTimeoutCheck(TossPaymentsProperties properties) {
        verify(properties.connectTimeout(), properties.readTimeout(), PaymentTransactionService.STALE_PENDING_AFTER);
    }

    static void verify(Duration connectTimeout, Duration readTimeout, Duration staleAfter) {
        // 승인 요청 한 번과 결과 불명일 때의 조회 한 번
        Duration maxWait = connectTimeout.plus(readTimeout).multipliedBy(2);
        if (staleAfter.compareTo(maxWait) <= 0) {
            throw new IllegalStateException("결제 PENDING 정리 기준(" + staleAfter.toSeconds()
                    + "s)이 승인·조회 최대 대기 시간(" + maxWait.toSeconds()
                    + "s)보다 길어야 합니다. grab.toss 타임아웃을 줄이거나 정리 기준을 늘리세요.");
        }
    }
}
