package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderDetailResponse;
import org.example.grab.domain.order.dto.OrderStatusResponse;
import org.example.grab.domain.order.dto.OrderSummaryResponse;
import org.example.grab.domain.order.dto.ShipmentCreateRequest;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.Shipment;
import org.example.grab.domain.order.repository.OrderQueryRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.SellerOrderRepository;
import org.example.grab.domain.order.repository.ShipmentRepository;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.ErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class SellerOrderService {

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final OrderQueryRepository orderQueryRepository;
    private final RequestHasher requestHasher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> findOrders(
            Long sellerUserId, Long dropId, OrderStatus orderStatus,
            String paymentStatus, int page, int size
    ) {
        return sellerOrderRepository.findOrders(
                sellerUserId, dropId, orderStatus == null ? null : orderStatus.name(),
                paymentStatus, page, size
        );
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse findOrder(Long sellerUserId, Long orderId) {
        assertOwnership(sellerUserId, orderId);
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        return OrderDetailResponse.from(order, orderQueryRepository.findLatestPaymentStatus(orderId),
                shipmentRepository.findByOrderId(orderId).orElse(null));
    }

    @Transactional
    public OrderStatusResponse prepareShipment(Long sellerUserId, Long orderId) {
        Order order = lockOwnedOrder(sellerUserId, orderId);
        if (sellerOrderRepository.hasUnknownCancellation(orderId)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
        order.prepareShipment();
        return OrderStatusResponse.from(order);
    }

    @Transactional
    public OrderStatusResponse ship(
            Long sellerUserId, Long orderId, String keyValue, ShipmentCreateRequest request
    ) {
        String key = IdempotencyKey.from(keyValue).value();
        String hash = requestHasher.hash(request);
        Order order = lockOwnedOrder(sellerUserId, orderId);
        Shipment existing = shipmentRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            if (!existing.getIdempotencyKey().equals(key) || !existing.getRequestHash().equals(hash)) {
                throw new BusinessException(ErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
            }
            return OrderStatusResponse.from(order);
        }
        if (order.getStatus() != OrderStatus.PREPARING) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
        order.ship();
        shipmentRepository.save(Shipment.create(
                order, key, hash, request.carrier(), request.trackingNumber(), request.shippedAt()
        ));
        return OrderStatusResponse.from(order);
    }

    @Transactional
    public OrderStatusResponse completeDelivery(Long sellerUserId, Long orderId) {
        Order order = lockOwnedOrder(sellerUserId, orderId);
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
        Shipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT));
        shipment.completeDelivery(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        order.completeDelivery();
        return OrderStatusResponse.from(order);
    }

    private Order lockOwnedOrder(Long sellerUserId, Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        assertOwnership(sellerUserId, orderId);
        return order;
    }

    private void assertOwnership(Long sellerUserId, Long orderId) {
        if (!sellerOrderRepository.ownsOrder(sellerUserId, orderId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }
    }
}
