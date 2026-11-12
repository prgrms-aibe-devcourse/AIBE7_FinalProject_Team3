package org.example.grab.domain.dashboard.repository;

import org.example.grab.domain.dashboard.dto.DropStatsProjection;
import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropProjection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SellerDashboardRepository {

    // dashboard 도메인이 drop 엔티티에 의존하지 않도록 JDBC로 필요한 컬럼만 조회한다
    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 기간은 주문·결제 집계에만 적용한다(SELLER.md 2.1). null이면 제한하지 않는다.
    private static final String PERIOD_CONDITION = """
            AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR %1$s >= :from)
            AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR %1$s <= :to)
            """;

    public List<UpcomingDropProjection> findUpcomingDrops(
            long sellerId, UpcomingDropEventType eventType, OffsetDateTime now, OffsetDateTime deadline) {
        String scheduleColumn = eventType == UpcomingDropEventType.START ? "sale_starts_at" : "sale_ends_at";
        String status = eventType == UpcomingDropEventType.START ? "WISH" : "GRAB";
        String sql = """
                SELECT d.id AS "dropId", d.name AS "name", d.status AS "status",
                       d.%s AS "upcomingAt"
                FROM drops d
                WHERE d.seller_id = :sellerId AND d.status = :status
                  AND d.%s > :now AND d.%s <= :deadline
                ORDER BY d.%s, d.id
                """.formatted(scheduleColumn, scheduleColumn, scheduleColumn, scheduleColumn);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("status", status)
                .addValue("now", now)
                .addValue("deadline", deadline);
        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new UpcomingDropProjection(
                resultSet.getLong("dropId"),
                resultSet.getString("name"),
                resultSet.getString("status"),
                resultSet.getObject("upcomingAt", OffsetDateTime.class)));
    }

    public record SummaryMetric(String metric, String status, long value) {}

    // 요약 항목은 한 요청에서 한 번만 DB 왕복한다. 기간은 주문·결제에만 적용한다.
    public List<SummaryMetric> findSummaryMetrics(long sellerId, OffsetDateTime from, OffsetDateTime to) {
        String sql = """
                WITH stock AS (
                    SELECT COALESCE(SUM(op.total_quantity - op.reserved_quantity - op.sold_quantity - op.withheld_quantity), 0) available,
                           COALESCE(SUM(op.reserved_quantity), 0) reserved,
                           COALESCE(SUM(op.sold_quantity), 0) sold
                    FROM drop_options op JOIN drops d ON d.id = op.drop_id
                    WHERE d.seller_id = :sellerId AND d.status <> 'CANCELED'
                ), metrics AS (
                    SELECT 'DROP' metric, d.status, COUNT(*) value FROM drops d
                    WHERE d.seller_id = :sellerId GROUP BY d.status
                    UNION ALL
                    SELECT 'ORDER', o.status, COUNT(*) FROM orders o
                    WHERE o.seller_id = :sellerId
                    """ + PERIOD_CONDITION.formatted("o.created_at") + "GROUP BY o.status\nUNION ALL\n" + """
                    SELECT 'PAYMENT', p.status, COUNT(*) FROM payments p JOIN orders o ON o.id = p.order_id
                    WHERE o.seller_id = :sellerId
                    """ + PERIOD_CONDITION.formatted("p.created_at") + "GROUP BY p.status\nUNION ALL\n" + """
                    SELECT 'RECONCILIATION', NULL, COUNT(*) FROM payments p JOIN orders o ON o.id = p.order_id
                    WHERE o.seller_id = :sellerId AND p.reconciliation_status = 'REQUIRED'
                )
                SELECT metric, status, value FROM metrics
                UNION ALL SELECT 'STOCK', 'AVAILABLE', available FROM stock
                UNION ALL SELECT 'STOCK', 'RESERVED', reserved FROM stock
                UNION ALL SELECT 'STOCK', 'SOLD', sold FROM stock
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("from", from, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("to", to, Types.TIMESTAMP_WITH_TIMEZONE);
        return jdbcTemplate.query(sql, parameters, (rs, row) ->
                new SummaryMetric(rs.getString("metric"), rs.getString("status"), rs.getLong("value")));
    }

    /*
     * DROP별 운영 지표(SELLER.md 2.2). 재고는 drop_options, 매출은 orders라서 한 번에 JOIN하면
     * 행이 곱해져 합계가 부풀므로 LATERAL로 각각 집계한 뒤 붙인다.
     * 재고 계산식은 sumStock과 같지만 여기서는 CANCELED DROP도 목록에 포함한다.
     * 매출은 결제가 확정된 주문(status IN ('PAID', 'PREPARING', 'SHIPPED', 'DELIVERED'))만 센다.
     * soldQuantity(order_items.quantity 합)는 order_items를 orders에 직접 JOIN하면 주문당 여러
     * 품목이 있을 때 order_count·salesAmount가 행 수만큼 부풀므로, 상관 서브쿼리로 주문별 합계를
     * 구한 뒤 SUM으로 다시 더한다.
     */
    public Page<DropStatsProjection> findDropStats(long sellerId, String status, Long dropId, Pageable pageable) {
        String statusCondition = "AND (CAST(:status AS TEXT) IS NULL OR d.status = :status)\n";
        String dropIdCondition = "AND (CAST(:dropId AS BIGINT) IS NULL OR d.id = :dropId)\n";
        String sql = """
                WITH page_drops AS MATERIALIZED (
                    SELECT d.id, d.name, d.status, d.sale_ends_at
                    FROM drops d WHERE d.seller_id = :sellerId
                    """ + statusCondition + dropIdCondition + """
                    ORDER BY d.id DESC LIMIT :size OFFSET :offset
                ), active_wishes AS (
                    SELECT w.drop_id, COUNT(*) active_count FROM wishes w
                    JOIN page_drops d ON d.id = w.drop_id WHERE w.canceled_at IS NULL GROUP BY w.drop_id
                ), stock AS (
                    SELECT op.drop_id,
                           COALESCE(SUM(op.total_quantity - op.reserved_quantity - op.sold_quantity - op.withheld_quantity), 0) available,
                           COALESCE(SUM(op.reserved_quantity), 0) reserved,
                           COALESCE(SUM(op.sold_quantity), 0) sold
                    FROM drop_options op JOIN page_drops d ON d.id = op.drop_id GROUP BY op.drop_id
                ), paid_orders AS MATERIALIZED (
                    SELECT o.id, o.drop_id, o.total_amount FROM orders o JOIN page_drops d ON d.id = o.drop_id
                    WHERE o.status IN ('PAID', 'PREPARING', 'SHIPPED', 'DELIVERED')
                ), item_totals AS (
                    SELECT oi.order_id, SUM(oi.quantity) quantity FROM order_items oi
                    JOIN paid_orders o ON o.id = oi.order_id GROUP BY oi.order_id
                ), order_stats AS (
                    SELECT o.drop_id, COUNT(*) order_count, SUM(o.total_amount) sales_amount,
                           SUM(COALESCE(i.quantity, 0)) sold_quantity
                    FROM paid_orders o LEFT JOIN item_totals i ON i.order_id = o.id GROUP BY o.drop_id
                )
                SELECT d.id AS "dropId", d.name AS "name", d.status AS "status", d.sale_ends_at AS "saleEndsAt",
                       COALESCE(w.active_count, 0) AS "activeWishCount",
                       COALESCE(st.available, 0) AS "availableStock", COALESCE(st.reserved, 0) AS "reservedStock",
                       COALESCE(st.sold, 0) AS "soldStock", COALESCE(ord.order_count, 0) AS "orderCount",
                       COALESCE(ord.sold_quantity, 0) AS "soldQuantity", COALESCE(ord.sales_amount, 0) AS "salesAmount"
                FROM page_drops d LEFT JOIN active_wishes w ON w.drop_id = d.id
                LEFT JOIN stock st ON st.drop_id = d.id LEFT JOIN order_stats ord ON ord.drop_id = d.id
                ORDER BY d.id DESC
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("status", status, Types.VARCHAR)
                .addValue("dropId", dropId, Types.BIGINT)
                .addValue("size", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        long started = System.nanoTime();
        List<DropStatsProjection> content = jdbcTemplate.query(sql, parameters,
                (resultSet, rowNumber) -> new DropStatsProjection(
                        resultSet.getLong("dropId"),
                        resultSet.getString("name"),
                        resultSet.getString("status"),
                        resultSet.getObject("saleEndsAt", OffsetDateTime.class),
                        resultSet.getLong("activeWishCount"),
                        resultSet.getLong("availableStock"),
                        resultSet.getLong("reservedStock"),
                        resultSet.getLong("soldStock"),
                        resultSet.getLong("orderCount"),
                        resultSet.getLong("soldQuantity"),
                        resultSet.getLong("salesAmount")));
        long contentUs = (System.nanoTime() - started) / 1_000;
        // 총 건수는 집계가 필요 없으므로 LATERAL 없이 센다.
        String countSql = "SELECT COUNT(*) FROM drops d WHERE d.seller_id = :sellerId\n"
                + statusCondition + dropIdCondition;
        started = System.nanoTime();
        Long total = jdbcTemplate.queryForObject(countSql, parameters, Long.class);
        long countUs = (System.nanoTime() - started) / 1_000;
        log.info("TEMP_DASHBOARD_SQL drop_stats_content_us={} count_us={} total_us={}", contentUs, countUs,
                contentUs + countUs);
        return new PageImpl<>(content, pageable, Objects.requireNonNullElse(total, 0L));
    }

}
