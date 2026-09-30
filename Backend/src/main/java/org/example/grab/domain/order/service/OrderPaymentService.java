package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.PayableOrder;
import org.example.grab.domain.order.dto.PaymentCompletionResult;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.ReleaseDestination;
import org.example.grab.domain.order.entity.ReleaseReason;
import org.example.grab.domain.order.entity.StockReservation;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderInventoryRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.global.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * 결제 결과를 반영하기 전에 주문 행을 잠근다. 결제 요청 검증과 같은 순서(주문 → 결제)로 잠가 교착을 피한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PayableOrder lockForPaymentResult(Long orderId) {
        return PayableOrder.from(lockOrder(orderId));
    }

    /**
     * PG 승인 성공을 주문에 반영한다. COMPLETED일 때만 주문 PAID, 예약 COMMITTED, 선점 수량을 판매 수량으로 옮긴다.
     * 결제 상태 기록과 같은 트랜잭션에서 실행돼야 하므로 호출하는 쪽의 트랜잭션을 요구한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentCompletionResult completePayment(
            Long orderId, long approvedAmount, OffsetDateTime approvedAt, OffsetDateTime now) {
        Order order = lockOrder(orderId);
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
        // 재고 반영 중에 예외로 롤백되면 승인된 결제 기록까지 사라지므로, 아무것도 바꾸기 전에 선점 수량을 먼저 확인한다.
        List<StockReservation> reservations = stockReservationRepository.findAllOfOrderSortedByOption(order.getId());
        if (!hasEnoughReserved(reservations)) {
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
     * 만료 대상이 아니면 아무것도 바꾸지 않고 false를 돌려준다. 결제 대기 만료 배치(GR-22)가 재사용한다.
     */
    @Transactional
    public boolean expireIfDue(Long orderId, OffsetDateTime now) {
        Order order = lockOrder(orderId);
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING || !order.isPaymentExpired(now)) {
            return false;
        }

        order.expire();
        for (StockReservation reservation : stockReservationRepository.findAllOfOrderSortedByOption(order.getId())) {
            reservation.release(ReleaseReason.EXPIRED, ReleaseDestination.AVAILABLE, now);
            requireOneRow(inventoryRepository.releaseReservedQuantity(
                    reservation.getOrderItem().getOptionId(), reservation.getOrderItem().getQuantity()));
        }
        return true;
    }

    // 옵션 행을 잠근 뒤 옵션별 예약 수량 합계만큼 선점 수량이 남아 있는지 확인한다.
    private boolean hasEnoughReserved(List<StockReservation> reservations) {
        Map<Long, Integer> required = new TreeMap<>();
        for (StockReservation reservation : reservations) {
            required.merge(reservation.getOrderItem().getOptionId(), reservation.getOrderItem().getQuantity(), Integer::sum);
        }
        if (required.isEmpty()) {
            return true;
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

    // 선점 수량이 예약보다 적으면 재고 원장이 이미 어긋난 것이므로 트랜잭션 전체를 되돌린다.
    // 결제 확정은 옵션 행을 잠그고 미리 확인하므로 여기까지 오지 않는다. 만료는 되돌려도 잃는 기록이 없다.
    private static void requireOneRow(int updatedRows) {
        if (updatedRows != 1) {
            throw new IllegalStateException("옵션 선점 수량이 예약 수량보다 적어 재고를 반영할 수 없습니다.");
        }
    }
}
