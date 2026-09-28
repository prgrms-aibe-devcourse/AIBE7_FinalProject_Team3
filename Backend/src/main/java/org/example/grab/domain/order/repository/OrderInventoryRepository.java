package org.example.grab.domain.order.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OrderInventoryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<DropSnapshot> findDrop(Long dropId) {
        String sql = """
                SELECT d.id, d.status, d.name, d.shipping_fee, d.sale_starts_at, d.sale_ends_at,
                       s.brand_name
                FROM drops d
                JOIN sellers s ON s.id = d.seller_id
                WHERE d.id = :dropId
                FOR SHARE OF d
                """;
        List<DropSnapshot> results = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("dropId", dropId),
                (resultSet, rowNumber) -> new DropSnapshot(
                        resultSet.getLong("id"),
                        resultSet.getString("status"),
                        resultSet.getString("name"),
                        resultSet.getString("brand_name"),
                        resultSet.getLong("shipping_fee"),
                        resultSet.getObject("sale_starts_at", OffsetDateTime.class),
                        resultSet.getObject("sale_ends_at", OffsetDateTime.class)
                )
        );
        return results.stream().findFirst();
    }

    /** 옵션 행을 항상 PK 오름차순으로 잠가 서로 다른 옵션 조합 주문 사이의 데드락을 방지한다. */
    public List<LockedOption> lockOptions(Long dropId, List<Long> optionIds) {
        String sql = """
                SELECT o.id, o.unit_price, o.total_quantity, o.reserved_quantity,
                       o.sold_quantity, o.withheld_quantity, o.is_active,
                       COALESCE((
                           SELECT string_agg(v.value, ' / ' ORDER BY g.sort_order)
                           FROM drop_option_value_maps m
                           JOIN drop_option_groups g ON g.id = m.group_id
                           JOIN drop_option_values v ON v.id = m.value_id
                           WHERE m.option_id = o.id
                       ), '기본') AS option_name
                FROM drop_options o
                WHERE o.drop_id = :dropId AND o.id IN (:optionIds)
                ORDER BY o.id
                FOR UPDATE OF o
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("dropId", dropId)
                .addValue("optionIds", optionIds);
        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new LockedOption(
                resultSet.getLong("id"),
                resultSet.getLong("unit_price"),
                resultSet.getInt("total_quantity"),
                resultSet.getInt("reserved_quantity"),
                resultSet.getInt("sold_quantity"),
                resultSet.getInt("withheld_quantity"),
                resultSet.getBoolean("is_active"),
                resultSet.getString("option_name")
        ));
    }

    public void increaseReservedQuantity(Long optionId, int quantity) {
        jdbcTemplate.update(
                """
                UPDATE drop_options
                SET reserved_quantity = reserved_quantity + :quantity,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :optionId
                """,
                new MapSqlParameterSource()
                        .addValue("optionId", optionId)
                        .addValue("quantity", quantity)
        );
    }

    public record DropSnapshot(
            Long id,
            String status,
            String productName,
            String sellerName,
            long shippingFee,
            OffsetDateTime saleStartsAt,
            OffsetDateTime saleEndsAt
    ) {
    }

    public record LockedOption(
            Long id,
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
}
