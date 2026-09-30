package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.dto.PayableOrder;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;

// 결제 요청 검증(트랜잭션 1)의 결과. PG 호출은 트랜잭션 밖에서 하므로 다음에 할 일을 값으로 돌려준다.
sealed interface PaymentPreparation {

    // 같은 멱등 키·같은 요청의 재전송: 새 결제 시도 없이 기존 결과를 돌려준다.
    record Replay(Payment payment, PayableOrder order) implements PaymentPreparation {
    }

    // 같은 요청의 재전송인데 그 결제가 아직 확정되지 않았다: PG 조회로 정리한 뒤 정리된 결과를 돌려준다.
    // 정리하지 않고 돌려주면 같은 요청으로는 UNKNOWN·멈춘 PENDING의 확정 결과를 영영 받지 못한다.
    // confirmable이 false면(취소·만료 등 결제할 수 없는 주문) 조회만 하고 승인은 다시 요청하지 않는다.
    record ResolveReplay(Long paymentId, PaymentConfirmCommand command, PayableOrder order, boolean confirmable)
            implements PaymentPreparation {
    }

    // PENDING을 저장했으니 PG 승인을 요청한다.
    record Ready(Long paymentId, PaymentConfirmCommand command, PayableOrder order) implements PaymentPreparation {
    }

    // 결과가 확정되지 않은 이전 결제가 새 결제를 막고 있어, PG 조회로 먼저 정리해야 한다.
    // command는 이전 결제의 승인 요청 그대로다. 승인 요청이 PG에 닿지 않았으면 같은 서버 멱등 키로 다시 보낸다.
    record Resolve(Long paymentId, PaymentConfirmCommand command) implements PaymentPreparation {
    }
}
