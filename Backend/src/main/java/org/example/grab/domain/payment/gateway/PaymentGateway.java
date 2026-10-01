package org.example.grab.domain.payment.gateway;

/*
    외부 PG 호출 창구. 결제 서비스는 이 인터페이스에만 의존하고, 토스페이먼츠 호출은 구현체에 둔다(TECHSTACK.md 4.4).
    구현체는 네트워크 오류·타임아웃을 예외로 던지지 않고 UNKNOWN 결과로 돌려준다.
    호출하는 쪽이 DB 트랜잭션 밖에서 부르는 것을 전제로 한다(ERD.md 3.2).
 */
public interface PaymentGateway {

    PaymentGatewayResult confirm(PaymentConfirmCommand command);

    // 승인 결과를 알 수 없을 때 PG에 실제 상태를 다시 묻는다.
    PaymentGatewayResult lookup(String paymentKey);
}
