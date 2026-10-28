package org.example.grab.domain.demand.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

// 저장된 수요 분석 설명(ERD.md 1.7 demand_explanations). DROP·입력 해시당 한 건이다
@Repository
@RequiredArgsConstructor
public class DemandExplanationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // explanationJson은 GENERATED일 때만 값이 있다
    public record StoredExplanation(String status, String explanationJson, OffsetDateTime createdAt) {
    }

    public Optional<StoredExplanation> find(long dropId, String inputHash) {
        String sql = """
                SELECT e.status, e.explanation, e.created_at
                FROM demand_explanations e
                WHERE e.drop_id = :dropId AND e.input_hash = :inputHash
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("dropId", dropId)
                .addValue("inputHash", inputHash);
        return jdbcTemplate.query(sql, parameters, (resultSet, rowNumber) -> new StoredExplanation(
                        resultSet.getString("status"),
                        resultSet.getString("explanation"),
                        resultSet.getObject("created_at", OffsetDateTime.class)))
                .stream().findFirst();
    }

    // 같은 입력으로 동시에 저장하면 먼저 저장된 행이 남는다(DEMAND.md 3.4)
    public void save(long dropId, String inputHash, String status, String explanationJson, String model,
                     String promptVersion, Integer tokens, OffsetDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO demand_explanations (drop_id, input_hash, status, explanation, llm_model, prompt_version,
                                                 tokens, created_at)
                VALUES (:dropId, :inputHash, :status, CAST(:explanation AS JSONB), :model, :promptVersion, :tokens,
                        :createdAt)
                ON CONFLICT (drop_id, input_hash) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("dropId", dropId)
                .addValue("inputHash", inputHash)
                .addValue("status", status)
                .addValue("explanation", explanationJson)
                .addValue("model", model)
                .addValue("promptVersion", promptVersion)
                .addValue("tokens", tokens)
                .addValue("createdAt", createdAt));
    }
}
