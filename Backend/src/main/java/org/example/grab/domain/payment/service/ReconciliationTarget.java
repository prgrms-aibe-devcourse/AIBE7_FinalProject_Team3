package org.example.grab.domain.payment.service;

import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;

import java.time.OffsetDateTime;

/**
 * 진행 중 결제 정리 배치가 선점한 결제 한 건. PG 호출은 트랜잭션 밖에서 하므로 필요한 값을 미리 읽어 둔다.
 *
 * @param command     이전 승인 요청 그대로의 명령. 승인을 다시 요청할 때 같은 서버 멱등 키를 쓴다
 * @param confirmable 읽은 시점에 주문이 결제 대기이고 마감 전인지. false면 조회만 하고 승인은 다시 요청하지 않는다
 */
record ReconciliationTarget(
        Long paymentId,
        PaymentConfirmCommand command,
        boolean confirmable,
        PaymentStatus status,
        OffsetDateTime createdAt
) {
}
