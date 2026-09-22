package org.example.grab.domain.order.repository;

import org.example.grab.domain.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findAllByOrderIdOrderByIdAsc(Long orderId);
}
