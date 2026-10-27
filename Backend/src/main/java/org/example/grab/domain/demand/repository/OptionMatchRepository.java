package org.example.grab.domain.demand.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/*
    옵션별 재고 배분(DEMAND.md 7절)의 후보 검색과 LLM 매칭 재사용(ERD.md 1.7 option_matches).
    후보 행에는 판매자 ID가 들어가지만 최소 집계 단위 판정에만 서버 안에서 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class OptionMatchRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 표본 DROP 하나의 (그룹명, 값)과 그 값이 들어간 SKU의 판매량 합
    public record OptionSales(long dropId, long sellerId, String groupName, String value, int sold) {
    }

    public List<OptionSales> findOptionSales(List<Long> dropIds) {
        if (dropIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT g.drop_id, d.seller_id, g.name AS group_name, v.value, SUM(o.sold_quantity) AS sold
                FROM drop_option_value_maps m
                JOIN drop_option_groups g ON g.id = m.group_id
                JOIN drop_option_values v ON v.id = m.value_id
                JOIN drop_options o ON o.id = m.option_id
                JOIN drops d ON d.id = g.drop_id
                WHERE g.drop_id IN (:dropIds)
                GROUP BY g.drop_id, d.seller_id, g.name, v.value
                """, new MapSqlParameterSource("dropIds", dropIds), (resultSet, rowNumber) -> new OptionSales(
                resultSet.getLong("drop_id"),
                resultSet.getLong("seller_id"),
                resultSet.getString("group_name"),
                resultSet.getString("value"),
                resultSet.getInt("sold")));
    }

    public Optional<String> findMatches(String inputHash) {
        return jdbcTemplate.queryForList("SELECT matches FROM option_matches WHERE input_hash = :inputHash",
                new MapSqlParameterSource("inputHash", inputHash), String.class).stream().findFirst();
    }

    // 같은 입력으로 동시에 저장하면 먼저 저장된 행이 남는다(DEMAND.md 7.4)
    public void saveMatches(String inputHash, String matchesJson, String model, String promptVersion,
                            OffsetDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO option_matches (input_hash, matches, llm_model, prompt_version, created_at)
                VALUES (:inputHash, CAST(:matches AS JSONB), :model, :promptVersion, :createdAt)
                ON CONFLICT (input_hash) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("inputHash", inputHash)
                .addValue("matches", matchesJson)
                .addValue("model", model)
                .addValue("promptVersion", promptVersion)
                .addValue("createdAt", createdAt));
    }
}
