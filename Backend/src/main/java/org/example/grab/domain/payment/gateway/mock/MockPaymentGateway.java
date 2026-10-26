package org.example.grab.domain.payment.gateway.mock;

import org.example.grab.domain.payment.gateway.PaymentCancelCommand;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
    부하테스트(GR-154, GR-155)에서 실제 토스페이먼츠 샌드박스를 호출하지 않도록 쓰는 가짜 PG.
    토스 샌드박스는 기능 검증용이라 장시간·고빈도 호출을 보내면 레이트리밋·키 정지로 이어질 수 있어,
    배치·트랜잭션·DB 쪽 부하만 보고 싶을 때는 이 구현체로 PaymentGateway를 바꿔치기한다(운영에서는 쓰지 않는다).
    항상 승인·취소에 성공한다. 실패를 섞고 싶으면(장애 주입) 별도 설정으로 확장한다.
 */
public class MockPaymentGateway implements PaymentGateway {

    private static final String NOT_FOUND_CODE = "MOCK_PAYMENT_NOT_FOUND";

    // resolve()가 이전 승인 결과를 lookup으로 다시 조회할 때 같은 결과를 돌려주기 위한 기록.
    private final ConcurrentHashMap<String, PaymentGatewayResult> approvals = new ConcurrentHashMap<>();
    // 같은 멱등 키로 취소를 다시 요청하면(GR-87 취소 재요청 배치) 최초 결과를 그대로 돌려주기 위한 기록.
    private final ConcurrentHashMap<String, PaymentCancelResult> cancellations = new ConcurrentHashMap<>();
    private final Clock clock;

    public MockPaymentGateway() {
        this(Clock.systemUTC());
    }

    MockPaymentGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public PaymentGatewayResult confirm(PaymentConfirmCommand command) {
        PaymentGatewayResult result = PaymentGatewayResult.approved(
                "DONE", command.orderId(), command.amount(), OffsetDateTime.now(clock));
        approvals.put(command.paymentKey(), result);
        return result;
    }

    @Override
    public PaymentGatewayResult lookup(String paymentKey) {
        PaymentGatewayResult approved = approvals.get(paymentKey);
        if (approved != null) {
            return approved;
        }
        return PaymentGatewayResult.notApproved(null, NOT_FOUND_CODE, "모킹된 결제 기록이 없습니다.");
    }

    @Override
    public PaymentCancelResult cancel(PaymentCancelCommand command) {
        return cancellations.computeIfAbsent(command.idempotencyKey(), key -> PaymentCancelResult.canceled(
                "CANCELED", UUID.randomUUID().toString(), OffsetDateTime.now(clock)));
    }
}
