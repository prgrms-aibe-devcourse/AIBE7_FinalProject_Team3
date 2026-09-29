package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.SellerOrderDetailResponse;
import org.example.grab.domain.order.dto.SellerOrderListProjection;
import org.example.grab.domain.order.dto.SellerOrderListResponse;
import org.example.grab.domain.order.dto.OrderStatusResponse;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.PaymentStatus;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.shipment.dto.ShipmentRegisterRequest;
import org.example.grab.domain.shipment.entity.Shipment;
import org.example.grab.domain.shipment.repository.ShipmentRepository;
import org.example.grab.domain.shipment.service.ShipmentRequestHasher;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// DROP 소유권을 확인하고 판매자 주문을 조회한다.
// 판매자 인증과 승인 여부는 인증 시점에 CurrentSellerIdProvider(ROLE_SELLER)가 판정한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerOrderService {

    private final OrderRepository orderRepository;
    private final OrderDetailReader orderDetailReader;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentRequestHasher shipmentRequestHasher;

    public SellerOrderDetailResponse findOrder(long sellerId, UUID orderId) {
        Order order = orderRepository.findByUuid(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!orderRepository.ownsDrop(sellerId, order.getDropId())) {
            throw new BusinessException(OrderErrorCode.ORDER_ACCESS_DENIED);
        }

        OrderDetailReader.OrderDetail detail = orderDetailReader.read(order);
        return SellerOrderDetailResponse.from(
                order,
                detail.items(),
                detail.paymentStatus(),
                detail.shipment());
    }

    @Transactional
    public OrderStatusResponse prepareShipment(long sellerId, UUID orderId) {
        Order order = orderRepository.findByUuidForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!orderRepository.ownsDrop(sellerId, order.getDropId())) {
            throw new BusinessException(OrderErrorCode.ORDER_ACCESS_DENIED);
        }
        if (order.getStatus() == OrderStatus.PAID && orderRepository.hasUnknownOrderCancellation(order.getId())) {
            throw new BusinessException(OrderErrorCode.PAYMENT_CANCELLATION_UNKNOWN);
        }

        order.prepareShipment();
        return new OrderStatusResponse(order.getUuid(), order.getStatus().name());
    }

    @Transactional
    public OrderStatusResponse registerShipment(
            long sellerId, UUID orderId, String idempotencyKeyHeader, ShipmentRegisterRequest request) {
        IdempotencyKey idempotencyKey = IdempotencyKey.from(idempotencyKeyHeader);
        RequestHash requestHash = shipmentRequestHasher.hash(request);
        Order order = orderRepository.findByUuidForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!orderRepository.ownsDrop(sellerId, order.getDropId())) {
            throw new BusinessException(OrderErrorCode.ORDER_ACCESS_DENIED);
        }

        Optional<Shipment> existingShipment = shipmentRepository.findByOrderId(order.getId());

        // 해당 주문에 배송 정보가 이미 있는지
        if (existingShipment.isPresent()) {
            Shipment shipment = existingShipment.get();
            // 기존 배송 정보의 키와 요청 키가 같으면 같은 요청 키를 사용한 재시도로 봄
            if (idempotencyKey.value().equals(shipment.getIdempotencyKey())) {
                // 멱등성 키는 같은데 요청 내용이 다르면 DUPLICATE_IDEMPOTENCY_KEY 오류 던짐
                if (!requestHash.value().equals(shipment.getRequestHash())) {
                    throw new BusinessException(CommonErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
                }
                // 송장 재요청 시 최초 응답 상태인 SHIPPED 반환
                return new OrderStatusResponse(order.getUuid(), OrderStatus.SHIPPED.name());
            }
            throw new BusinessException(CommonErrorCode.ORDER_STATUS_CONFLICT);
        }

        order.ship();
        shipmentRepository.save(Shipment.register(order, request.carrier(), request.trackingNumber(),
                idempotencyKey.value(), requestHash.value(), OffsetDateTime.now()));
        return new OrderStatusResponse(order.getUuid(), order.getStatus().name());
    }

    public PageResponse<SellerOrderListResponse> findOrders(
            long sellerId, Long dropId, OrderStatus orderStatus, PaymentStatus paymentStatus, int page, int size) {
        if (dropId != null) {
            if (!orderRepository.existsDrop(dropId)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            if (!orderRepository.ownsDrop(sellerId, dropId)) {
                throw new BusinessException(CommonErrorCode.ACCESS_DENIED);
            }
        }
        Page<SellerOrderListResponse> orders = orderRepository.findSellerOrders(
                sellerId, dropId, orderStatus == null ? null : orderStatus.name(),
                paymentStatus == null ? null : paymentStatus.name(), PageRequest.of(page, size))
                .map(SellerOrderService::toListResponse);
        List<SellerOrderListResponse> content = orders.getContent();
        return new PageResponse<>(content, page, size, orders.getTotalElements(),
                orders.getTotalPages(), orders.hasNext());
    }

    private static SellerOrderListResponse toListResponse(SellerOrderListProjection projection) {
        return new SellerOrderListResponse(
                projection.getOrderId(), projection.getOrderNumber(), projection.getDropId(),
                projection.getProductName(), projection.getSellerName(), projection.getOrderStatus(),
                projection.getPaymentStatus(), projection.getItemsAmount(), projection.getShippingAmount(),
                projection.getTotalAmount(), projection.getOrderedAt());
    }
}
