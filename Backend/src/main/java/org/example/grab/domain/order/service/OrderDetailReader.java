package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.PaymentStatus;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.shipment.entity.Shipment;
import org.example.grab.domain.shipment.repository.ShipmentRepository;
import org.springframework.stereotype.Component;

import java.util.List;

// 구매자·판매자 주문 상세가 같은 항목·결제·배송 데이터를 보도록 조회를 한곳에 모은다.
// 접근 권한 확인은 호출하는 서비스가 먼저 끝낸다.
@Component
@RequiredArgsConstructor
class OrderDetailReader {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentRepository shipmentRepository;

    OrderDetail read(Order order) {
        return new OrderDetail(
                order,
                orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId()),
                orderRepository.findLatestPaymentStatus(order.getId()).map(PaymentStatus::valueOf).orElse(null),
                shipmentRepository.findByOrderId(order.getId()).orElse(null)
        );
    }

    record OrderDetail(Order order, List<OrderItem> items, PaymentStatus paymentStatus, Shipment shipment) {
    }
}
