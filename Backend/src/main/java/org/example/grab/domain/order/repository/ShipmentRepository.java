package org.example.grab.domain.order.repository;

import org.example.grab.domain.order.entity.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByOrderId(Long orderId);

    Optional<Shipment> findByIdempotencyKey(String idempotencyKey);
}
