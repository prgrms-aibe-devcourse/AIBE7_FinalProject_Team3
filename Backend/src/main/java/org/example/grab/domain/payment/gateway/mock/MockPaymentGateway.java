package org.example.grab.domain.payment.gateway.mock;

import org.example.grab.domain.payment.gateway.PaymentCancelCommand;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/*
    부하테스트(GR-154, GR-155)에서 실제 토스페이먼츠 샌드박스를 호출하지 않도록 쓰는 가짜 PG.
    토스 샌드박스는 기능 검증용이라 장시간·고빈도 호출을 보내면 레이트리밋·키 정지로 이어질 수 있어,
    배치·트랜잭션·DB 쪽 부하만 보고 싶을 때는 이 구현체로 PaymentGateway를 바꿔치기한다(운영에서는 쓰지 않는다).
    기본은 항상 성공이고, GR-155(PG 장애 주입)에서는 grab.payment.mock-gateway의 비율 설정으로
    승인 거절·결과불명·취소 결과불명을 섞는다. 결과불명은 즉시 풀어주지 않고
    resolveDelay가 지난 뒤에야 확정시켜서, PaymentService의 즉시 조회로 바로 끝나버리지 않고
    PaymentBatchService(진행 중 결제 정리·취소 재요청)가 나중에 다시 물어봐서 해결하는
    경로를 실제로 태우게 한다.
 */
public class MockPaymentGateway implements PaymentGateway {

    private static final String NOT_FOUND_CODE = "MOCK_PAYMENT_NOT_FOUND";
    private static final String INJECTED_FAILURE_CODE = "MOCK_INJECTED_FAILURE";
    // pendingCancels에서 "결과불명 없이 즉시 취소"로 결정됐음을 표시하는 표지값.
    private static final Instant IMMEDIATE = Instant.MIN;

    // resolve()/lookup()이 확정된 승인 결과를 다시 조회할 때 같은 결과를 돌려주기 위한 기록.
    private final ConcurrentHashMap<String, PaymentGatewayResult> approvals = new ConcurrentHashMap<>();
    // confirm()에서 결과불명으로 떨어진 결제가, resolveDelay가 지나야 조회로 확정되게 하기 위한 보류 기록.
    private final ConcurrentHashMap<String, PendingConfirm> pendingConfirms = new ConcurrentHashMap<>();
    // 같은 멱등 키로 취소를 다시 요청하면(GR-87 취소 재요청 배치) 최초 결과를 그대로 돌려주기 위한 기록.
    private final ConcurrentHashMap<String, PaymentCancelResult> cancellations = new ConcurrentHashMap<>();
    // cancel()에서 결과불명으로 떨어진 취소가, resolveDelay가 지나야 재요청으로 확정되게 하기 위한 보류 기록.
    private final ConcurrentHashMap<String, Instant> pendingCancels = new ConcurrentHashMap<>();
    private final Clock clock;
    private final MockPaymentGatewayProperties properties;
    // 테스트가 Math.random() 대신 확률을 주입할 수 있게 하기 위함. 기본은 실제 난수.
    private final DoubleSupplier random;

    private record PendingConfirm(String orderId, long amount, Instant readyAt) {
    }

    public MockPaymentGateway(MockPaymentGatewayProperties properties) {
        this(properties, Clock.systemUTC(), Math::random);
    }

    MockPaymentGateway(MockPaymentGatewayProperties properties, Clock clock, DoubleSupplier random) {
        this.properties = properties;
        this.clock = clock;
        this.random = random;
    }

    @Override
    public PaymentGatewayResult confirm(PaymentConfirmCommand command) {
        // 실패·결과불명 구간을 한 번의 난수로 나눠, 설정한 두 비율이 서로 독립적으로 적용되게 한다
        // (각각 따로 굴리면 실제 결과불명 비율이 설정값보다 낮아진다).
        double roll = random.getAsDouble();
        if (roll < properties.confirmFailureRate()) {
            return PaymentGatewayResult.notApproved(null, INJECTED_FAILURE_CODE, "장애 주입: 결제 승인 거절");
        }
        if (roll < properties.confirmFailureRate() + properties.confirmUnknownRate()) {
            Instant readyAt = clock.instant().plusSeconds(properties.resolveDelaySeconds());
            pendingConfirms.put(command.paymentKey(), new PendingConfirm(command.orderId(), command.amount(), readyAt));
            return PaymentGatewayResult.unknown(null, INJECTED_FAILURE_CODE, "장애 주입: 결제 승인 결과 불명");
        }
        return approve(command.paymentKey(), command.orderId(), command.amount());
    }

    @Override
    public PaymentGatewayResult lookup(String paymentKey) {
        PaymentGatewayResult approved = approvals.get(paymentKey);
        if (approved != null) {
            return approved;
        }
        PendingConfirm pending = pendingConfirms.get(paymentKey);
        if (pending != null) {
            if (clock.instant().isBefore(pending.readyAt())) {
                return PaymentGatewayResult.unknown(null, INJECTED_FAILURE_CODE, "장애 주입: 결제 승인 결과 불명(대기 중)");
            }
            // 먼저 확정 결과를 기록(동시 조회끼리도 최초 결과로 수렴)한 뒤에 보류 기록을 치운다.
            PaymentGatewayResult result = approve(paymentKey, pending.orderId(), pending.amount());
            pendingConfirms.remove(paymentKey);
            return result;
        }
        // 동시 호출이 그 사이 위 분기를 먼저 끝내 approve()는 됐는데 pendingConfirms.remove()까지
        // 끝난 뒤일 수 있어, 이 스레드가 pendingConfirms를 못 보고 왔더라도 approvals를 한 번 더 본다.
        approved = approvals.get(paymentKey);
        if (approved != null) {
            return approved;
        }
        return PaymentGatewayResult.notApproved(null, NOT_FOUND_CODE, "모킹된 결제 기록이 없습니다.");
    }

    // computeIfAbsent로 동시 호출이 겹쳐도 같은 paymentKey에는 최초 승인 결과만 기록·반환한다.
    private PaymentGatewayResult approve(String paymentKey, String orderId, long amount) {
        return approvals.computeIfAbsent(paymentKey,
                key -> PaymentGatewayResult.approved("DONE", orderId, amount, OffsetDateTime.now(clock)));
    }

    @Override
    public PaymentCancelResult cancel(PaymentCancelCommand command) {
        PaymentCancelResult cached = cancellations.get(command.idempotencyKey());
        if (cached != null) {
            return cached;
        }
        // computeIfAbsent로 결과불명 여부 추첨을 키당 한 번만 실행해, 같은 키로 동시에 들어와도
        // 결과가 갈리지 않게 한다(먼저 온 호출의 추첨 결과로 수렴).
        Instant readyAt = pendingCancels.computeIfAbsent(command.idempotencyKey(), key ->
                random.getAsDouble() < properties.cancelUnknownRate()
                        ? clock.instant().plusSeconds(properties.resolveDelaySeconds())
                        : IMMEDIATE);
        if (!readyAt.equals(IMMEDIATE) && clock.instant().isBefore(readyAt)) {
            // 동시 호출이 그 사이 이미 확정했을 수 있다(바로 아래에서 cancellations에 먼저 쓰고
            // pendingCancels를 나중에 지우므로, 이 스레드는 지워지기 전의 pendingCancels를 보고
            // 다시 추첨해 버린 것일 수 있음). 재추첨 결과를 쓰기 전에 이미 끝난 결과가 있는지 본다.
            PaymentCancelResult resolved = cancellations.get(command.idempotencyKey());
            if (resolved != null) {
                return resolved;
            }
            return PaymentCancelResult.unknown(null, INJECTED_FAILURE_CODE, "장애 주입: 결제 취소 결과 불명(대기 중)");
        }
        // 먼저 확정 결과를 기록한 뒤에 보류 기록을 치운다(동시 호출이 그 사이 재추첨해도
        // 결국 이 결과로 수렴하도록).
        PaymentCancelResult result = cancellations.computeIfAbsent(command.idempotencyKey(), key -> PaymentCancelResult.canceled(
                "CANCELED", UUID.randomUUID().toString(), OffsetDateTime.now(clock)));
        pendingCancels.remove(command.idempotencyKey());
        return result;
    }
}
