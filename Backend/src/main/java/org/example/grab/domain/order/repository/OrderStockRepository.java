package org.example.grab.domain.order.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class OrderStockRepository {

    private final JdbcClient jdbcClient;

    public OrderStockRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean lockBuyer(Long buyerId) {
        return jdbcClient.sql("SELECT id FROM users WHERE id = :buyerId FOR UPDATE")
                .param("buyerId", buyerId)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    public DropForOrder lockDrop(Long dropId) {
        return jdbcClient.sql("""
                        SELECT d.id,
                               d.status,
                               d.name,
                               d.shipping_fee,
                               d.sale_starts_at,
                               d.sale_ends_at,
                               s.brand_name
                        FROM drops d
                        JOIN sellers s ON s.id = d.seller_id
                        WHERE d.id = :dropId
                        FOR UPDATE OF d
                        """)
                .param("dropId", dropId)
                .query((rs, rowNum) -> new DropForOrder(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getString("name"),
                        rs.getLong("shipping_fee"),
                        rs.getObject("sale_starts_at", OffsetDateTime.class),
                        rs.getObject("sale_ends_at", OffsetDateTime.class),
                        rs.getString("brand_name")
                ))
                .optional()
                .orElse(null);
    }

    public List<OptionForOrder> lockOptions(List<Long> optionIds) {
        if (optionIds.isEmpty()) {
            return List.of();
        }

        List<OptionStock> stocks = jdbcClient.sql("""
                        SELECT id, drop_id, unit_price, total_quantity,
                               reserved_quantity, sold_quantity, withheld_quantity, is_active
                        FROM drop_options
                        WHERE id IN (:optionIds)
                        ORDER BY id
                        FOR UPDATE
                        """)
                .param("optionIds", optionIds)
                .query((rs, rowNum) -> new OptionStock(
                        rs.getLong("id"),
                        rs.getLong("drop_id"),
                        rs.getLong("unit_price"),
                        rs.getInt("total_quantity"),
                        rs.getInt("reserved_quantity"),
                        rs.getInt("sold_quantity"),
                        rs.getInt("withheld_quantity"),
                        rs.getBoolean("is_active")
                ))
                .list();

        Map<Long, String> optionNames = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT o.id,
                               COALESCE(string_agg(v.value, ' / ' ORDER BY g.sort_order), '기본') AS option_name
                        FROM drop_options o
                        LEFT JOIN drop_option_value_maps m ON m.option_id = o.id
                        LEFT JOIN drop_option_groups g ON g.id = m.group_id
                        LEFT JOIN drop_option_values v ON v.id = m.value_id
                        WHERE o.id IN (:optionIds)
                        GROUP BY o.id
                        ORDER BY o.id
                        """)
                .param("optionIds", optionIds)
                .query((rs, rowNum) -> Map.entry(rs.getLong("id"), rs.getString("option_name")))
                .list()
                .forEach(entry -> optionNames.put(entry.getKey(), entry.getValue()));

        return stocks.stream()
                .map(stock -> new OptionForOrder(
                        stock.id(),
                        stock.dropId(),
                        stock.unitPrice(),
                        stock.totalQuantity(),
                        stock.reservedQuantity(),
                        stock.soldQuantity(),
                        stock.withheldQuantity(),
                        stock.active(),
                        optionNames.getOrDefault(stock.id(), "기본")
                ))
                .toList();
    }

    public void increaseReservedQuantity(Long optionId, int quantity) {
        int updated = jdbcClient.sql("""
                        UPDATE drop_options
                        SET reserved_quantity = reserved_quantity + :quantity,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :optionId
                          AND total_quantity - reserved_quantity - sold_quantity - withheld_quantity >= :quantity
                        """)
                .param("optionId", optionId)
                .param("quantity", quantity)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("잠근 옵션의 재고 변경에 실패했습니다.");
        }
    }

    public record DropForOrder(
            Long id,
            String status,
            String name,
            long shippingFee,
            OffsetDateTime saleStartsAt,
            OffsetDateTime saleEndsAt,
            String sellerName
    ) {
    }

    public record OptionForOrder(
            Long id,
            Long dropId,
            long unitPrice,
            int totalQuantity,
            int reservedQuantity,
            int soldQuantity,
            int withheldQuantity,
            boolean active,
            String optionName
    ) {

        public int availableQuantity() {
            return totalQuantity - reservedQuantity - soldQuantity - withheldQuantity;
        }
    }

    private record OptionStock(
            Long id,
            Long dropId,
            long unitPrice,
            int totalQuantity,
            int reservedQuantity,
            int soldQuantity,
            int withheldQuantity,
            boolean active
    ) {
    }
}
