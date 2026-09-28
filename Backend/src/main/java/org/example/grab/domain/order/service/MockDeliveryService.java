package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderStatusResponse;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.Shipment;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.ShipmentRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MockDeliveryService {

    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;

    @Transactional
    public OrderStatusResponse completeDelivery(UUID orderId) {
        Order order = orderRepository.findByUuidForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        if (order.getStatus() == OrderStatus.DELIVERED) {
            return new OrderStatusResponse(order.getUuid(), order.getStatus().name());
        }
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION);
        }

        Shipment shipment = shipmentRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION));
        OffsetDateTime deliveredAt = OffsetDateTime.now();
        order.completeDelivery();
        shipment.markDelivered(deliveredAt);

        return new OrderStatusResponse(order.getUuid(), order.getStatus().name());
    }
}
