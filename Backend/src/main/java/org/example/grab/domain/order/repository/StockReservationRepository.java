package org.example.grab.domain.order.repository;

import org.example.grab.domain.order.entity.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    Optional<StockReservation> findByOrderItemId(Long orderItemId);

    // 옵션 ID 순서로 가져와 재고 행을 항상 같은 순서로 갱신한다. 동시에 도는 확정·만료 트랜잭션끼리 교착을 피하기 위함이다.
    @Query("""
            select r from StockReservation r
            join fetch r.orderItem i
            where i.order.id = :orderId
            order by i.optionId, i.id
            """)
    List<StockReservation> findAllOfOrderSortedByOption(@Param("orderId") Long orderId);
}
