package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.CancelableOrder;
import org.example.grab.domain.order.dto.PayableOrder;
import org.example.grab.domain.order.dto.PaymentCompletionResult;
import org.example.grab.domain.order.dto.PaymentExpiryCandidate;
import org.example.grab.domain.order.dto.PaymentExpiryResult;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.ReleaseDestination;
import org.example.grab.domain.order.entity.ReleaseReason;
import org.example.grab.domain.order.entity.ReservationStatus;
import org.example.grab.domain.order.entity.StockReservation;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderInventoryRepository;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/*
    결제 도메인이 주문·예약·재고를 바꿀 때 거치는 창구(CODING_CONVENTION.md 2.1).
    결제 확정과 만료는 모두 주문 행을 SELECT ... FOR UPDATE로 잠근 뒤 PAYMENT_PENDING인지 다시 확인하므로,
    두 경로가 동시에 실행돼도 먼저 잠근 쪽만 재고를 바꾼다(ERD.md 3.2). 판단 근거는 PostgreSQL 행 잠금뿐이다.
 */
@Service
@RequiredArgsConstructor
public class OrderPaymentService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockReservationRepository stockReservationRepository;
    private final OrderInventoryRepository inventoryRepository;

    /**
     * 결제 요청을 검증하는 동안 주문 행을 잠근다. 호출하는 결제 트랜잭션이 끝날 때까지 같은 주문의 다른 결제 요청은 기다린다.
     * 다른 구매자의 주문은 존재 여부를 숨기고 ORDER_NOT_FOUND로 응답한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PayableOrder lockForPaymentRequest(long buyerId, UUID orderId) {
        Order order = orderRepository.findByUuidAndBuyerIdForUpdate(orderId, buyerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        return PayableOrder.from(order);
    }

    /**
     * 소비자 취소 요청을 검증하는 동안 주문 행을 잠근다(ERD.md 3.3). 같은 주문의 취소·결제 확정·만료·배송 처리는 기다린다.
     * 다른 구매자의 주문은 존재 여부를 숨기고 ORDER_NOT_FOUND로 응답한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CancelableOrder lockForCancel(long buyerId, UUID orderId) {
        Order order = orderRepository.findByUuidAndBuyerIdForUpdate(orderId, buyerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        return CancelableOrder.from(order);
    }

    /**
     * 결제 전 주문을 취소하고 선점 재고를 가용 재고로 되돌린다(ERD.md 3.3). 진행 중인 결제 확인은 호출하는 결제 도메인이
     * 같은 트랜잭션에서 먼저 한다.
     *
     * @param lockedOrder 같은 트랜잭션에서 lockForCancel로 잠근 주문. 다시 잠그지 않는다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CancelableOrder cancelUnpaid(
            CancelableOrder lockedOrder, String idempotencyKey, String requestHash, String reason, OffsetDateTime now) {
        Order order = findLocked(lockedOrder);
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }

        order.requestCancel(idempotencyKey, requestHash, reason);
        order.cancel(now);
        releaseHeld(order, ReleaseReason.ORDER_CANCELED, now);
        return CancelableOrder.from(order);
    }

    /**
     * 결제 후 취소 요청을 주문에 기록한다. 주문 상태는 PG 결제 취소 결과를 받은 뒤 바꾼다(ERD.md 3.3).
     * 기록이 남아 있는 동안 다른 키의 취소 요청과 판매자 배송 처리를 막는다.
     *
     * @param lockedOrder 같은 트랜잭션에서 lockForCancel로 잠근 주문
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CancelableOrder requestPaidCancel(
            CancelableOrder lockedOrder, String idempotencyKey, String requestHash, String reason) {
        Order order = findLocked(lockedOrder);
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PREPARING) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }
        order.requestCancel(idempotencyKey, requestHash, reason);
        return CancelableOrder.from(order);
    }

    /**
     * PG 결제 취소 결과를 반영하기 전에 주문 행을 잠근다. 취소 요청 검증과 같은 순서(주문 → 결제 → 취소 기록)로 잠근다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CancelableOrder lockForCancelResult(Long orderId) {
        return CancelableOrder.from(lockOrder(orderId));
    }

    /**
     * PG 결제 취소 성공을 주문에 반영한다. 주문 CANCELED, COMMITTED 예약을 ORDER_CANCELED·AVAILABLE로 해제하고
     * 판매 수량을 가용 재고로 되돌린다. 결제 취소 기록과 같은 트랜잭션에서 반영한다(ERD.md 3.3).
     *
     * @param lockedOrder 같은 트랜잭션에서 lockForCancelResult로 잠근 주문
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CancelableOrder cancelPaid(CancelableOrder lockedOrder, OffsetDateTime now) {
        Order order = findLocked(lockedOrder);
        order.cancel(now);
        for (StockReservation reservation : stockReservationRepository.findAllOfOrderSortedByOption(order.getId())) {
            reservation.releaseCommitted(ReleaseDestination.AVAILABLE, now);
            requireOneRow(inventoryRepository.returnSoldQuantity(
                    reservation.getOrderItem().getOptionId(), reservation.getOrderItem().getQuantity()));
        }
        return CancelableOrder.from(order);
    }

    /**
     * PG가 결제 취소를 거절했다. 주문은 그대로 두고 취소 요청 기록을 비워 새 취소 요청을 받는다.
     *
     * @param lockedOrder 같은 트랜잭션에서 lockForCancelResult로 잠근 주문
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CancelableOrder clearCancelRequest(CancelableOrder lockedOrder) {
        Order order = findLocked(lockedOrder);
        order.clearCancelRequest();
        return CancelableOrder.from(order);
    }

    /**
     * 결제 결과를 반영하기 전에 주문 행을 잠근다. 결제 요청 검증과 같은 순서(주문 → 결제)로 잠가 교착을 피한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PayableOrder lockForPaymentResult(Long orderId) {
        return PayableOrder.from(lockOrder(orderId));
    }

    /**
     * PG 승인 성공을 주문에 반영한다. COMPLETED일 때만 주문 PAID, 예약 COMMITTED, 선점 수량을 판매 수량으로 옮긴다.
     * 결제 상태 기록과 같은 트랜잭션에서 실행돼야 하므로 호출하는 쪽의 트랜잭션을 요구한다.
     *
     * @param lockedOrder 같은 트랜잭션에서 lockForPaymentResult로 잠근 주문. 이미 잠근 행이므로 다시 잠그지 않고
     *                    영속성 컨텍스트의 주문 엔티티를 그대로 쓴다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentCompletionResult completePayment(
            PayableOrder lockedOrder, long approvedAmount, OffsetDateTime approvedAt, OffsetDateTime now) {
        Order order = orderRepository.findById(lockedOrder.id())
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() == OrderStatus.EXPIRED) {
            return PaymentCompletionResult.EXPIRED;
        }
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            return PaymentCompletionResult.NOT_PAYABLE;
        }
        // 만료 배치가 아직 돌지 않았어도 마감이 지났으면 결제를 확정하지 않는다(PAY-004).
        if (order.isPaymentExpired(now)) {
            return PaymentCompletionResult.EXPIRED;
        }
        if (approvedAmount != order.getTotalAmount()) {
            return PaymentCompletionResult.AMOUNT_MISMATCH;
        }
        // 예약 전이·재고 반영 중에 예외로 롤백되면 승인된 결제 기록까지 사라지므로, 아무것도 바꾸기 전에 원장을 먼저 확인한다.
        List<StockReservation> reservations = stockReservationRepository.findAllOfOrderSortedByOption(order.getId());
        if (!isReservationConsistent(order, reservations) || !hasEnoughReserved(reservations)) {
            return PaymentCompletionResult.INVENTORY_INCONSISTENT;
        }

        order.markPaid(approvedAt);
        for (StockReservation reservation : reservations) {
            reservation.commit(now);
            requireOneRow(inventoryRepository.commitReservedQuantity(
                    reservation.getOrderItem().getOptionId(), reservation.getOrderItem().getQuantity()));
        }
        return PaymentCompletionResult.COMPLETED;
    }

    /**
     * 결제 마감이 지난 PAYMENT_PENDING 주문을 만료하고 선점 재고를 가용 재고로 되돌린다.
     * 만료 대상이 아니면 아무것도 바꾸지 않고 false를 돌려준다. 다른 트랜잭션이 주문을 잠그고 있으면 기다린다.
     */
    @Transactional
    public boolean expireIfDue(Long orderId, OffsetDateTime now) {
        return expireIfDue(lockOrder(orderId), now);
    }

    /**
     * 결제 대기 만료 후보를 잠그지 않고 (payment_expires_at, id) 순서로 읽는다(GR-22, ERD.md 3.2).
     *
     * @param after 이전 조회의 마지막 후보. 첫 조회는 null
     */
    @Transactional(readOnly = true)
    public List<PaymentExpiryCandidate> findPaymentExpiryCandidates(
            OffsetDateTime now, PaymentExpiryCandidate after, int limit) {
        if (after == null) {
            return orderRepository.findPaymentExpiryCandidates(now, limit);
        }
        return orderRepository.findPaymentExpiryCandidatesAfter(now, after.getPaymentExpiresAt(), after.getId(), limit);
    }

    /**
     * 만료 배치가 주문 한 건을 만료한다. 주문 행을 FOR UPDATE SKIP LOCKED로 잠가, 결제 확정 등 다른 트랜잭션이 잠근 주문은
     * 기다리지 않고 건너뛴다. 잠근 뒤 상태·마감을 다시 확인하므로 반복·동시 실행에도 재고는 한 번만 반환된다.
     * 결제 상태는 보지 않는다. 진행 중인 결제가 있어도 마감이 지났으면 만료한다(ERD.md 3.2).
     */
    @Transactional
    public PaymentExpiryResult expireIfDueSkippingLocked(Long orderId, OffsetDateTime now) {
        Optional<Order> locked = orderRepository.findByIdForUpdateSkipLocked(orderId);
        if (locked.isEmpty()) {
            // 잠긴 행과 없는 행을 구분하지 않는다. 후보로 읽은 주문은 지워지지 않으므로 잠긴 것으로 본다.
            return PaymentExpiryResult.SKIPPED_LOCKED;
        }
        return expireIfDue(locked.get(), now) ? PaymentExpiryResult.EXPIRED : PaymentExpiryResult.NOT_DUE;
    }

    // 잠근 주문을 만료한다. 결제 확정과 같은 기준(Order.isPaymentExpired)으로 마감을 판정한다.
    private boolean expireIfDue(Order order, OffsetDateTime now) {
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING || !order.isPaymentExpired(now)) {
            return false;
        }

        order.expire(now);
        releaseHeld(order, ReleaseReason.EXPIRED, now);
        return true;
    }

    // 결제 전 만료·취소: HELD 예약을 해제하고 선점 수량을 가용 재고로 되돌린다. 옵션 ID 순서로 갱신해 교착을 피한다.
    private void releaseHeld(Order order, ReleaseReason reason, OffsetDateTime now) {
        for (StockReservation reservation : stockReservationRepository.findAllOfOrderSortedByOption(order.getId())) {
            reservation.release(reason, ReleaseDestination.AVAILABLE, now);
            requireOneRow(inventoryRepository.releaseReservedQuantity(
                    reservation.getOrderItem().getOptionId(), reservation.getOrderItem().getQuantity()));
        }
    }

    // 주문 생성은 주문 항목마다 HELD 예약을 하나씩 만든다. 수가 다르거나 이미 확정·해제된 예약이 있으면 원장이 어긋난 것이다.
    private boolean isReservationConsistent(Order order, List<StockReservation> reservations) {
        return !reservations.isEmpty()
                && reservations.size() == orderItemRepository.countByOrderId(order.getId())
                && reservations.stream().allMatch(reservation -> reservation.getStatus() == ReservationStatus.HELD);
    }

    // 옵션 행을 잠근 뒤 옵션별 예약 수량 합계만큼 선점 수량이 남아 있는지 확인한다.
    private boolean hasEnoughReserved(List<StockReservation> reservations) {
        Map<Long, Integer> required = new TreeMap<>();
        for (StockReservation reservation : reservations) {
            required.merge(reservation.getOrderItem().getOptionId(), reservation.getOrderItem().getQuantity(), Integer::sum);
        }
        Map<Long, Integer> reserved = new HashMap<>();
        for (OrderInventoryRepository.ReservedQuantity locked : inventoryRepository.lockReservedQuantities(required.keySet())) {
            reserved.put(locked.getId(), locked.getReservedQuantity());
        }
        return required.entrySet().stream()
                .allMatch(entry -> reserved.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    // 같은 트랜잭션에서 이미 잠근 주문이다. 다시 잠그지 않고 영속성 컨텍스트의 주문 엔티티를 그대로 쓴다.
    private Order findLocked(CancelableOrder lockedOrder) {
        return orderRepository.findById(lockedOrder.id())
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    // 선점 수량이 예약보다 적으면 재고 원장이 이미 어긋난 것이므로 트랜잭션 전체를 되돌린다.
    // 결제 확정은 옵션 행을 잠그고 미리 확인하므로 여기까지 오지 않는다. 만료는 되돌려도 잃는 기록이 없다.
    private static void requireOneRow(int updatedRows) {
        if (updatedRows != 1) {
            throw new IllegalStateException("옵션 선점 수량이 예약 수량보다 적어 재고를 반영할 수 없습니다.");
        }
    }
}
