package org.example.grab.domain.dashboard.repository;

import org.example.grab.domain.dashboard.dto.DropStatsProjection;
import org.example.grab.domain.dashboard.dto.SellerDashboardSummaryResponse.StockSummary;
import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
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

    // drop 상태별 갯수 반환
    public Map<String, Long> countDropsByStatus(long sellerId) {
        String sql = """
                SELECT d.status, COUNT(*)
                FROM drops d
                WHERE d.seller_id = :sellerId
                GROUP BY d.status
                """;
        return queryCounts(sql, new MapSqlParameterSource("sellerId", sellerId));
    }

    // order 상태별 갯수 반환
    public Map<String, Long> countOrdersByStatus(long sellerId, OffsetDateTime from, OffsetDateTime to) {
        String sql = """
                SELECT o.status, COUNT(*)
                FROM orders o
                JOIN drops d ON d.id = o.drop_id AND d.seller_id = :sellerId
                WHERE TRUE
                """ + PERIOD_CONDITION.formatted("o.created_at") + "GROUP BY o.status";
        return queryCounts(sql, periodParameters(sellerId, from, to));
    }

    // 결제 상태별 갯수 반환
    // 주문이 아니라 결제 시도 건수이므로 재시도가 있으면 주문 집계보다 많다(SELLER.md 2.1).
    public Map<String, Long> countPaymentsByStatus(long sellerId, OffsetDateTime from, OffsetDateTime to) {
        String sql = """
                SELECT p.status, COUNT(*)
                FROM payments p
                JOIN orders o ON o.id = p.order_id
                JOIN drops d ON d.id = o.drop_id AND d.seller_id = :sellerId
                WHERE TRUE
                """ + PERIOD_CONDITION.formatted("p.created_at") + "GROUP BY p.status";
        return queryCounts(sql, periodParameters(sellerId, from, to));
    }

    // 미해결 보정은 기간을 걸면 오래 방치된 건이 사라지므로 전체를 센다(SELLER.md 2.1).
    public long countReconciliationRequired(long sellerId) {
        String sql = """
                SELECT COUNT(*)
                FROM payments p
                JOIN orders o ON o.id = p.order_id
                JOIN drops d ON d.id = o.drop_id AND d.seller_id = :sellerId
                WHERE p.reconciliation_status = 'REQUIRED'
                """;
        Long count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("sellerId", sellerId), Long.class);
        return Objects.requireNonNullElse(count, 0L);
    }

    // 가용 재고는 저장하지 않고 ERD.md 1.2절 계산식으로 구한다. 취소된 DROP은 판매분이 아니므로 제외한다.
    public StockSummary sumStock(long sellerId) {
        String sql = """
                SELECT COALESCE(SUM(op.total_quantity - op.reserved_quantity
                                    - op.sold_quantity - op.withheld_quantity), 0) AS available,
                       COALESCE(SUM(op.reserved_quantity), 0) AS reserved,
                       COALESCE(SUM(op.sold_quantity), 0) AS sold
                FROM drop_options op
                JOIN drops d ON d.id = op.drop_id AND d.seller_id = :sellerId
                WHERE d.status <> 'CANCELED'
                """;
        return jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("sellerId", sellerId),
                (resultSet, rowNumber) -> new StockSummary(
                        resultSet.getLong("available"),
                        resultSet.getLong("reserved"),
                        resultSet.getLong("sold")));
    }

    /*
     * DROP별 운영 지표(SELLER.md 2.2). 재고는 drop_options, 매출은 orders라서 한 번에 JOIN하면
     * 행이 곱해져 합계가 부풀므로 LATERAL로 각각 집계한 뒤 붙인다.
     * 재고 계산식은 sumStock과 같지만 여기서는 CANCELED DROP도 목록에 포함한다.
     * 매출은 결제가 확정되고(paid_at) 취소되지 않은 주문만 센다. 상태 이름을 나열하지 않아
     * 주문 상태가 늘어도 집계 기준이 흔들리지 않는다.
     */
    public Page<DropStatsProjection> findDropStats(long sellerId, String status, Pageable pageable) {
        String statusCondition = "AND (CAST(:status AS TEXT) IS NULL OR d.status = :status)\n";
        String sql = """
                SELECT d.id AS "dropId", d.name AS "name", d.status AS "status",
                       d.sale_ends_at AS "saleEndsAt",
                       (SELECT COUNT(*) FROM wishes w
                         WHERE w.drop_id = d.id AND w.canceled_at IS NULL) AS "activeWishCount",
                       st.available AS "availableStock", st.reserved AS "reservedStock",
                       st.sold AS "soldStock",
                       ord.order_count AS "orderCount", ord.sales_amount AS "salesAmount"
                FROM drops d
                LEFT JOIN LATERAL (
                    SELECT COALESCE(SUM(op.total_quantity - op.reserved_quantity
                                        - op.sold_quantity - op.withheld_quantity), 0) AS available,
                           COALESCE(SUM(op.reserved_quantity), 0) AS reserved,
                           COALESCE(SUM(op.sold_quantity), 0) AS sold
                    FROM drop_options op WHERE op.drop_id = d.id
                ) st ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS order_count, COALESCE(SUM(o.total_amount), 0) AS sales_amount
                    FROM orders o
                    WHERE o.drop_id = d.id AND o.paid_at IS NOT NULL AND o.canceled_at IS NULL
                ) ord ON TRUE
                WHERE d.seller_id = :sellerId
                """ + statusCondition + """
                ORDER BY d.id DESC
                LIMIT :size OFFSET :offset
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                .addValue("status", status, Types.VARCHAR)
                .addValue("size", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
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
                        resultSet.getLong("salesAmount")));
        // 총 건수는 집계가 필요 없으므로 LATERAL 없이 센다.
        String countSql = "SELECT COUNT(*) FROM drops d WHERE d.seller_id = :sellerId\n" + statusCondition;
        Long total = jdbcTemplate.queryForObject(countSql, parameters, Long.class);
        return new PageImpl<>(content, pageable, Objects.requireNonNullElse(total, 0L));
    }

    // SQL에 넘길 값 꾸러미 만들기
    // MapSqlParameterSource에 NamedParameterJdbcTemplate SQL의 sellerId, from, to에 꽂을 값을 담음
    private MapSqlParameterSource periodParameters(long sellerId, OffsetDateTime from, OffsetDateTime to) {
        return new MapSqlParameterSource()
                .addValue("sellerId", sellerId)
                // null을 넘길 때 PostgreSQL이 타입을 추론하지 못하므로 타입을 함께 지정한다
                .addValue("from", from, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("to", to, Types.TIMESTAMP_WITH_TIMEZONE);
    }

    // GROUP BY 결과를 Map으로 바꾸기
    private Map<String, Long> queryCounts(String sql, MapSqlParameterSource parameters) {
        return jdbcTemplate.query(sql, parameters,
                        (resultSet, rowNumber) -> Map.entry(resultSet.getString(1), resultSet.getLong(2)))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
