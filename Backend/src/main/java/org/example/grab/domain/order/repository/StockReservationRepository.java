package org.example.grab.domain.order.repository;

import org.example.grab.domain.order.entity.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    Optional<StockReservation> findByOrderItemId(Long orderItemId);
}
