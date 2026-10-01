package org.example.grab.domain.dashboard.repository;

import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class SellerDashboardRepository {

    // dashboard 도메인이 drop 엔티티에 의존하지 않도록 JDBC로 필요한 컬럼만 조회한다
    private final NamedParameterJdbcTemplate jdbcTemplate;

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
}
