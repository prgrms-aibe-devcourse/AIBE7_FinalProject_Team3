package org.example.grab.domain.shipment.repository;

import org.example.grab.domain.shipment.entity.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByOrderId(Long orderId);

    /*
     * 배송 단계 자동 진행(GR-86). SHIPPED 주문의 Shipment만 대상이고 마지막 단계(OUT_FOR_DELIVERY) 전까지만 올린다.
     * DropRepository의 배치 쿼리와 같은 패턴으로, 후보 행을 잠가(FOR UPDATE SKIP LOCKED) 같은 문장에서 갱신한다.
     * 엔티티 로딩·dirty checking을 거치지 않으므로 updated_at을 직접 갱신한다.
     */
    @Modifying
    @Query(value = """
            UPDATE shipments
            SET delivery_stage = CASE delivery_stage
                    WHEN 'COLLECTED' THEN 'LINE_HAUL_OUT'
                    WHEN 'LINE_HAUL_OUT' THEN 'LINE_HAUL_IN'
                    WHEN 'LINE_HAUL_IN' THEN 'OUT_FOR_DELIVERY'
                END,
                stage_updated_at = :now,
                updated_at = :now
            WHERE id IN (
                SELECT s.id FROM shipments s
                JOIN orders o ON o.id = s.order_id
                WHERE o.status = 'SHIPPED'
                  AND s.delivery_stage IN ('COLLECTED', 'LINE_HAUL_OUT', 'LINE_HAUL_IN')
                  AND s.stage_updated_at <= :cutoff
                ORDER BY s.stage_updated_at, s.id
                LIMIT :batchSize
                FOR UPDATE OF s SKIP LOCKED
            )
            """, nativeQuery = true)
    int advanceStageBatch(
            @Param("now") OffsetDateTime now, @Param("cutoff") OffsetDateTime cutoff,
            @Param("batchSize") int batchSize);

    /*
     * 마지막 단계(OUT_FOR_DELIVERY)에서 간격이 더 지난 대상의 주문 ID를 잠가 확보한다.
     * 실제 DELIVERED 전환(orders.status)은 OrderRepository.markDelivered가 같은 트랜잭션에서 수행한다.
     * MockDeliveryService.completeDelivery가 order -> shipment 순으로 잠그므로,
     * 교착 상태를 막기 위해 여기서도 order 행(FOR UPDATE OF o)을 먼저 잠근다.
     */
    @Query(value = """
            SELECT s.order_id FROM shipments s
            JOIN orders o ON o.id = s.order_id
            WHERE o.status = 'SHIPPED'
              AND s.delivery_stage = 'OUT_FOR_DELIVERY'
              AND s.stage_updated_at <= :cutoff
            ORDER BY s.stage_updated_at, s.id
            LIMIT :batchSize
            FOR UPDATE OF o SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockDeliveryCompletionTargets(
            @Param("cutoff") OffsetDateTime cutoff, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = "UPDATE shipments SET delivered_at = :now, updated_at = :now WHERE order_id IN (:orderIds)",
            nativeQuery = true)
    int markDelivered(@Param("orderIds") List<Long> orderIds, @Param("now") OffsetDateTime now);
}
