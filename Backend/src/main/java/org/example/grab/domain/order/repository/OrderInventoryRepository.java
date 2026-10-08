package org.example.grab.domain.order.repository;

import org.example.grab.domain.order.entity.Order;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 주문 생성·결제 확정·만료 트랜잭션에서 DROP 판매 조건과 옵션 재고를 잠금 조회하고 선점·판매 수량을 반영한다.
public interface OrderInventoryRepository extends Repository<Order, Long> {

    @Query(value = """
            SELECT d.id AS "id", d.status AS "status", d.name AS "productName",
                   s.brand_name AS "sellerName", d.shipping_fee AS "shippingFee",
                   d.sale_starts_at AS "saleStartsAt", d.sale_ends_at AS "saleEndsAt"
            FROM drops d
            JOIN sellers s ON s.id = d.seller_id
            WHERE d.id = :dropId
            FOR SHARE OF d
            """, nativeQuery = true)
    Optional<DropSnapshot> findDrop(@Param("dropId") Long dropId);

    /** 옵션 행을 항상 PK 오름차순으로 잠가 서로 다른 옵션 조합 주문 사이의 데드락을 방지한다. */
    @Query(value = """
            SELECT o.id AS "id", o.unit_price AS "unitPrice", o.total_quantity AS "totalQuantity",
                   o.reserved_quantity AS "reservedQuantity", o.sold_quantity AS "soldQuantity",
                   o.withheld_quantity AS "withheldQuantity", o.is_active AS "active",
                   COALESCE((
                       SELECT string_agg(v.value, ' / ' ORDER BY g.sort_order)
                       FROM drop_option_value_maps m
                       JOIN drop_option_groups g ON g.id = m.group_id
                       JOIN drop_option_values v ON v.id = m.value_id
                       WHERE m.option_id = o.id
                   ), '기본') AS "optionName"
            FROM drop_options o
            WHERE o.drop_id = :dropId AND o.id IN (:optionIds)
            ORDER BY o.id
            FOR UPDATE OF o
            """, nativeQuery = true)
    List<LockedOption> lockOptions(@Param("dropId") Long dropId, @Param("optionIds") List<Long> optionIds);

    @Modifying
    @Query(value = """
            UPDATE drop_options
            SET reserved_quantity = reserved_quantity + :quantity,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :optionId
            """, nativeQuery = true)
    void increaseReservedQuantity(@Param("optionId") Long optionId, @Param("quantity") int quantity);

    // 결제 확정 전에 옵션 행을 PK 오름차순으로 잠그고 선점 수량을 읽는다. 잠근 뒤라 확인한 수량이 확정 UPDATE까지 유지된다.
    @Query(value = """
            SELECT o.id AS "id", o.reserved_quantity AS "reservedQuantity"
            FROM drop_options o
            WHERE o.id IN (:optionIds)
            ORDER BY o.id
            FOR UPDATE OF o
            """, nativeQuery = true)
    List<ReservedQuantity> lockReservedQuantities(@Param("optionIds") Collection<Long> optionIds);

    // 결제 성공: 선점 수량을 판매 수량으로 옮긴다. 선점이 모자라면 0행을 돌려주고, 호출하는 쪽이 정합성 오류로 처리한다.
    @Modifying
    @Query(value = """
            UPDATE drop_options
            SET reserved_quantity = reserved_quantity - :quantity,
                sold_quantity = sold_quantity + :quantity,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :optionId AND reserved_quantity >= :quantity
            """, nativeQuery = true)
    int commitReservedQuantity(@Param("optionId") Long optionId, @Param("quantity") int quantity);

    // 결제 전 만료·실패: 선점 수량을 가용 재고로 되돌린다.
    @Modifying
    @Query(value = """
            UPDATE drop_options
            SET reserved_quantity = reserved_quantity - :quantity,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :optionId AND reserved_quantity >= :quantity
            """, nativeQuery = true)
    int releaseReservedQuantity(@Param("optionId") Long optionId, @Param("quantity") int quantity);

    // 결제 후 주문 취소: 판매 수량을 가용 재고로 되돌린다(반환 목적지 AVAILABLE, ERD.md 3.3).
    // 판매 수량이 모자라면 0행을 돌려주고, 호출하는 쪽이 정합성 오류로 처리한다.
    @Modifying
    @Query(value = """
            UPDATE drop_options
            SET sold_quantity = sold_quantity - :quantity,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :optionId AND sold_quantity >= :quantity
            """, nativeQuery = true)
    int returnSoldQuantity(@Param("optionId") Long optionId, @Param("quantity") int quantity);

    interface DropSnapshot {

        Long getId();

        String getStatus();

        String getProductName();

        String getSellerName();

        long getShippingFee();

        // 네이티브 조회는 TIMESTAMPTZ를 Instant로 반환하고 OffsetDateTime 변환기가 없어 Instant로 받는다.
        Instant getSaleStartsAt();

        Instant getSaleEndsAt();
    }

    interface ReservedQuantity {

        Long getId();

        int getReservedQuantity();
    }

    interface LockedOption {

        Long getId();

        long getUnitPrice();

        int getTotalQuantity();

        int getReservedQuantity();

        int getSoldQuantity();

        int getWithheldQuantity();

        boolean isActive();

        String getOptionName();

        default int getAvailableQuantity() {
            return getTotalQuantity() - getReservedQuantity() - getSoldQuantity() - getWithheldQuantity();
        }
    }
}
