package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.order.dto.PayableOrder;
import org.example.grab.domain.order.dto.PaymentCompletionResult;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentEvent;
import org.example.grab.domain.payment.entity.PaymentEventResult;
import org.example.grab.domain.payment.entity.PaymentEventSource;
import org.example.grab.domain.payment.entity.PaymentEventType;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.error.PaymentErrorCode;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.domain.payment.repository.PaymentEventRepository;
import org.example.grab.domain.payment.repository.PaymentRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
    결제 요청의 DB 트랜잭션 두 개를 맡는다(ERD.md 3.2). PG 호출은 이 두 트랜잭션 사이, 트랜잭션 밖에서 PaymentService가 한다.
    - prepare: 주문 행을 잠근 채 멱등성·주문 상태·금액·진행 중인 결제를 확인하고 PENDING을 저장한다.
    - applyResult: 주문 → 결제 순서로 행을 잠그고 PG 결과를 결제·주문·예약·재고에 반영한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    // 승인과 즉시 조회의 최대 대기 시간((연결 + 응답 타임아웃) × 2)보다 길게 잡아, 아직 승인 요청 중인 PENDING을 정리하지 않게 한다.
    // 이 관계는 PendingPaymentTimeoutCheck가 기동할 때 확인한다.
    static final Duration STALE_PENDING_AFTER = Duration.ofSeconds(60);

    private static final PaymentProvider PROVIDER = PaymentProvider.TOSS;
    private static final List<PaymentStatus> IN_PROGRESS = List.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN);
    private static final Set<OrderStatus> PAID_STATUSES =
            EnumSet.of(OrderStatus.PAID, OrderStatus.PREPARING, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    private static final String CONFIRM_EVENT_KEY = "confirm";

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderPaymentService orderPaymentService;
    private final ObjectMapper objectMapper;

    @Transactional
    public PaymentPreparation prepare(
            long buyerId,
            UUID orderId,
            IdempotencyKey idempotencyKey,
            RequestHash requestHash,
            PaymentRequest request,
            OffsetDateTime now
    ) {
        PayableOrder order = orderPaymentService.lockForPaymentRequest(buyerId, orderId);

        // 멱등성 확인을 상태 검증보다 먼저 한다. 결제가 끝난 뒤의 재전송도 409가 아니라 최초 결과를 받아야 한다.
        Optional<Payment> existing =
                paymentRepository.findByOrderIdAndClientIdempotencyKey(order.id(), idempotencyKey.value());
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if (!payment.getRequestHash().equals(requestHash.value())) {
                throw new BusinessException(CommonErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
            }
            // 조회는 주문 상태·마감과 관계없이 한다. 마감 뒤에 승인된 결제도 보정 대상으로 드러나야 한다.
            // 승인 재요청은 새로 청구하는 동작이므로 아직 결제할 수 있는 주문에만 허용한다.
            if (payment.isInProgress() && isResolvable(payment, now)) {
                return new PaymentPreparation.ResolveReplay(
                        payment.getId(), confirmCommand(payment, order), order, order.isPayable(now));
            }
            return new PaymentPreparation.Replay(payment, order);
        }

        validatePayable(order, request, now);
        // 승인은 됐지만 불일치로 주문을 확정하지 못해 보정 대상인 결제가 있다. 새로 승인하면 같은 주문에 청구가 쌓인다.
        if (paymentRepository.existsByOrderIdAndStatus(order.id(), PaymentStatus.SUCCEEDED)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        List<Payment> inProgress = paymentRepository.findByOrderIdAndStatusInOrderByIdAsc(order.id(), IN_PROGRESS);
        if (!inProgress.isEmpty()) {
            return inProgress.stream()
                    .filter(payment -> isResolvable(payment, now))
                    .findFirst()
                    .map(payment -> (PaymentPreparation) new PaymentPreparation.Resolve(
                            payment.getId(), confirmCommand(payment, order)))
                    .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED));
        }
        if (paymentRepository.existsByProviderAndProviderPaymentId(PROVIDER, request.paymentKey())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        Payment payment = paymentRepository.save(Payment.request(
                order.id(), PROVIDER, idempotencyKey.value(), requestHash.value(),
                request.paymentKey(), request.amount()));
        return new PaymentPreparation.Ready(payment.getId(), confirmCommand(payment, order), order);
    }

    private static PaymentConfirmCommand confirmCommand(Payment payment, PayableOrder order) {
        return new PaymentConfirmCommand(
                payment.getProviderPaymentId(), order.orderNumber(), payment.getAmount(), payment.getIdempotencyKey());
    }

    /**
     * PG 결과를 반영한다. 승인 결과가 불명이라 조회까지 했다면 두 결과를 모두 기록하고 조회 결과로 판단한다.
     *
     * @param confirmResult 승인 응답. 이전 결제를 조회로 정리할 때는 null
     * @param lookupResult  조회 응답. 조회하지 않았으면 null
     */
    @Transactional
    public Payment applyResult(
            Long paymentId,
            PaymentGatewayResult confirmResult,
            PaymentGatewayResult lookupResult,
            OffsetDateTime now
    ) {
        return applyResult(paymentId, null, confirmResult, lookupResult, now);
    }

    /**
     * 이전 결제를 조회로 정리하다 승인을 다시 요청했을 때 쓴다. 재요청을 판단한 조회도 판단 근거가 아닌 응답으로 기록한다.
     *
     * @param priorLookup 승인 재요청 전에 한 조회 응답. 없으면 null
     */
    @Transactional
    public Payment applyResult(
            Long paymentId,
            PaymentGatewayResult priorLookup,
            PaymentGatewayResult confirmResult,
            PaymentGatewayResult lookupResult,
            OffsetDateTime now
    ) {
        Long orderId = paymentRepository.findOrderIdById(paymentId)
                .orElseThrow(() -> new IllegalStateException("결제 시도가 없습니다: " + paymentId));
        PayableOrder order = orderPaymentService.lockForPaymentResult(orderId);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
        if (!payment.isInProgress()) {
            // 다른 요청이 먼저 결과를 확정했다. 확정된 결과를 다시 바꾸지 않는다.
            return payment;
        }

        PaymentGatewayResult decisive = lookupResult != null ? lookupResult : confirmResult;
        PaymentEventResult processing = apply(payment, order, decisive, now);
        if (priorLookup != null) {
            record(payment, "lookup:" + UUID.randomUUID(), PaymentEventType.LOOKUP, priorLookup,
                    PaymentEventResult.RECONCILIATION_REQUIRED);
        }
        if (confirmResult != null) {
            PaymentEventResult confirmProcessing = lookupResult != null
                    ? PaymentEventResult.RECONCILIATION_REQUIRED
                    : processing;
            // 이전 결제를 정리하며 승인을 다시 요청했다면 최초 승인 기록을 덮지 않고 별도 이벤트로 남긴다.
            String confirmEventKey = paymentEventRepository.existsByPaymentIdAndEventKey(payment.getId(), CONFIRM_EVENT_KEY)
                    ? "confirm-retry:" + UUID.randomUUID()
                    : CONFIRM_EVENT_KEY;
            record(payment, confirmEventKey, PaymentEventType.CONFIRM, confirmResult, confirmProcessing);
        }
        if (lookupResult != null) {
            record(payment, "lookup:" + UUID.randomUUID(), PaymentEventType.LOOKUP, lookupResult, processing);
        }
        return payment;
    }

    private void validatePayable(PayableOrder order, PaymentRequest request, OffsetDateTime now) {
        if (PAID_STATUSES.contains(order.status())) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        if (order.status() == OrderStatus.EXPIRED) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_EXPIRED);
        }
        // 취소된 주문, 이후 추가될 상태 등 결제 대기가 아닌 주문은 PG 승인까지 가지 않게 한다.
        if (order.status() != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        // 결제 대기지만 마감이 지났다. 판정은 승인 재요청(ResolveReplay)과 같은 PayableOrder.isPayable을 쓴다.
        if (!order.isPayable(now)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_EXPIRED);
        }
        if (request.amount() != order.totalAmount()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }

    // UNKNOWN은 언제든 조회로 정리한다. PENDING은 승인 요청이 아직 진행 중일 수 있어 충분히 오래된 것만 정리한다.
    private boolean isResolvable(Payment payment, OffsetDateTime now) {
        if (payment.getStatus() == PaymentStatus.UNKNOWN) {
            return true;
        }
        return payment.getCreatedAt() != null && !payment.getCreatedAt().plus(STALE_PENDING_AFTER).isAfter(now);
    }

    private PaymentEventResult apply(
            Payment payment, PayableOrder order, PaymentGatewayResult result, OffsetDateTime now) {
        return switch (result.outcome()) {
            case APPROVED -> applyApproved(payment, order, result, now);
            case NOT_APPROVED -> {
                payment.fail(result.failureCode(), result.failureMessage());
                yield PaymentEventResult.REJECTED;
            }
            case UNKNOWN -> {
                payment.markUnknown(unknownReason(result));
                yield PaymentEventResult.RECONCILIATION_REQUIRED;
            }
        };
    }

    private PaymentEventResult applyApproved(
            Payment payment, PayableOrder order, PaymentGatewayResult result, OffsetDateTime now) {
        // PG가 승인한 주문번호·금액이 서버 값과 다르면 주문을 확정하지 않는다(PAY-002).
        if (!order.orderNumber().equals(result.orderId())
                || result.totalAmount() == null || result.totalAmount() != payment.getAmount()) {
            log.error("PG 승인 응답이 서버 주문과 불일치: orderNumber={}, paymentId={}", order.orderNumber(), payment.getUuid());
            payment.succeedRequiringReconciliation(result.approvedAt(), "PG 승인 응답의 주문번호 또는 금액 불일치");
            return PaymentEventResult.RECONCILIATION_REQUIRED;
        }

        PaymentCompletionResult completion = orderPaymentService.completePayment(
                order.id(), result.totalAmount(), result.approvedAt(), now);
        if (completion == PaymentCompletionResult.COMPLETED) {
            payment.succeed(result.approvedAt());
            return PaymentEventResult.APPLIED;
        }
        // 승인은 됐지만 주문을 확정할 수 없다. 환불 등 보정이 필요하다(PAY-004).
        log.warn("승인된 결제를 주문에 반영하지 못함: orderNumber={}, reason={}", order.orderNumber(), completion);
        payment.succeedRequiringReconciliation(result.approvedAt(), reconciliationReason(completion));
        return PaymentEventResult.RECONCILIATION_REQUIRED;
    }

    private void record(
            Payment payment,
            String eventKey,
            PaymentEventType type,
            PaymentGatewayResult result,
            PaymentEventResult processing
    ) {
        if (paymentEventRepository.existsByPaymentIdAndEventKey(payment.getId(), eventKey)) {
            return;
        }
        paymentEventRepository.save(PaymentEvent.record(
                payment.getId(), eventKey, type, PaymentEventSource.API, processing,
                payload(result), result.approvedAt()));
    }

    // PG 응답 중 판단에 쓴 필드만 남긴다. paymentKey·카드 정보 등은 저장하지 않는다(ERD.md 1.5).
    private String payload(PaymentGatewayResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", result.outcome().name());
        payload.put("pgStatus", result.pgStatus());
        payload.put("orderId", result.orderId());
        payload.put("totalAmount", result.totalAmount());
        payload.put("approvedAt", result.approvedAt() == null ? null : result.approvedAt().toString());
        payload.put("failureCode", result.failureCode());
        payload.put("failureMessage", result.failureMessage());
        return objectMapper.writeValueAsString(payload);
    }

    private static String reconciliationReason(PaymentCompletionResult completion) {
        return switch (completion) {
            case EXPIRED -> "결제 마감 후 승인";
            case AMOUNT_MISMATCH -> "주문 금액과 승인 금액 불일치";
            case NOT_PAYABLE -> "결제 대기 상태가 아닌 주문의 승인";
            case INVENTORY_INCONSISTENT -> "예약·선점 재고 불일치로 주문 확정 불가";
            case COMPLETED -> throw new IllegalArgumentException("완료된 결제는 보정 대상이 아닙니다.");
        };
    }

    /*
        운영자가 보정 대상을 보고 원인을 구분할 수 있게 PG 상태와 오류 코드를 사유에 남긴다.
        승인 요청 전 상태는 PG가 곧 인증을 만료시키므로, 승인됐을 수 있는 결과 불명과 따로 표시한다.
     */
    private static String unknownReason(PaymentGatewayResult result) {
        String reason = result.awaitingConfirmation() ? "PG 승인 요청 전 상태" : "PG 승인 결과 확인 불가";
        String detail = Stream.of(result.pgStatus(), result.failureCode())
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        return detail.isEmpty() ? reason : reason + " (" + detail + ")";
    }
}
