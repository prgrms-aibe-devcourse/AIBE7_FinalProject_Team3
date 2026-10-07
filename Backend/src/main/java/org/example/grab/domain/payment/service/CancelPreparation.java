package org.example.grab.domain.payment.service;

import org.example.grab.domain.payment.dto.OrderCancelResponse;
import org.example.grab.domain.payment.gateway.PaymentCancelCommand;

// 주문 취소 검증(트랜잭션 1)의 결과. PG 호출은 트랜잭션 밖에서 하므로 다음에 할 일을 값으로 돌려준다.
sealed interface CancelPreparation {

    // PG 호출 없이 끝났다: 결제 전 취소, 또는 끝난 취소의 재전송.
    record Done(OrderCancelResponse response) implements CancelPreparation {
    }

    // PG 결제 취소를 요청한다. 진행 중인 취소의 재전송이면 command는 최초 요청과 같은 서버 멱등 키를 쓴다.
    record RequestPgCancel(Long cancellationId, PaymentCancelCommand command) implements CancelPreparation {
    }
}
