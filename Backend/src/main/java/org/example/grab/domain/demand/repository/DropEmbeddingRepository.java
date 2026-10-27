package org.example.grab.domain.demand.repository;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.OptionGroup;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.Source;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// DROP 임베딩(ERD.md 1.7 drop_embeddings)과 임베딩 입력이 되는 공개 카탈로그 텍스트를 읽고 쓴다
@Repository
@RequiredArgsConstructor
public class DropEmbeddingRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 공개된(DRAFT가 아닌) DROP만 입력을 돌려준다. 공개 후에는 상품 정보를 바꿀 수 없다
    public Optional<Source> findSource(long dropId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("dropId", dropId);
        Optional<String[]> drop = jdbcTemplate.query("""
                        SELECT c.name AS category_name, d.name, d.description
                        FROM drops d
                        JOIN categories c ON c.id = d.category_id
                        WHERE d.id = :dropId AND d.status <> 'DRAFT'
                        """, parameters, (resultSet, rowNumber) -> new String[]{
                        resultSet.getString("category_name"),
                        resultSet.getString("name"),
                        resultSet.getString("description")})
                .stream().findFirst();
        if (drop.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, String> groupNames = new LinkedHashMap<>();
        Map<Long, List<String>> groupValues = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT g.id AS group_id, g.name AS group_name, v.value
                FROM drop_option_groups g
                JOIN drop_option_values v ON v.group_id = g.id
                WHERE g.drop_id = :dropId
                ORDER BY g.sort_order, g.id, v.sort_order, v.id
                """, parameters, resultSet -> {
            long groupId = resultSet.getLong("group_id");
            groupNames.putIfAbsent(groupId, resultSet.getString("group_name"));
            groupValues.computeIfAbsent(groupId, id -> new ArrayList<>()).add(resultSet.getString("value"));
        });
        List<OptionGroup> optionGroups = groupNames.entrySet().stream()
                .map(entry -> new OptionGroup(entry.getValue(), List.copyOf(groupValues.get(entry.getKey()))))
                .toList();
        String[] row = drop.get();
        return Optional.of(new Source(row[0], row[1], row[2], optionGroups));
    }

    // 임베딩이 없거나 다른 모델로 만든 공개 DROP을 id 순으로 고른다(수요 분석 API 6.3)
    public List<Long> findTargets(String model, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT d.id
                FROM drops d
                LEFT JOIN drop_embeddings e ON e.drop_id = d.id
                WHERE d.status <> 'DRAFT' AND (e.drop_id IS NULL OR e.model <> :model)
                ORDER BY d.id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("model", model)
                .addValue("limit", limit), Long.class);
    }

    public Optional<String> findSourceHash(long dropId) {
        return jdbcTemplate.queryForList("""
                SELECT source_hash FROM drop_embeddings WHERE drop_id = :dropId
                """, new MapSqlParameterSource("dropId", dropId), String.class).stream().findFirst();
    }

    // 여러 서버가 같은 DROP을 동시에 만들어도 같은 입력의 결과라 덮어써도 된다
    public void save(long dropId, float[] embedding, String model, String sourceHash, OffsetDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO drop_embeddings (drop_id, embedding, model, source_hash, created_at, updated_at)
                VALUES (:dropId, CAST(:embedding AS vector), :model, :sourceHash, :now, :now)
                ON CONFLICT (drop_id) DO UPDATE
                SET embedding = EXCLUDED.embedding,
                    model = EXCLUDED.model,
                    source_hash = EXCLUDED.source_hash,
                    updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource()
                .addValue("dropId", dropId)
                .addValue("embedding", toVectorLiteral(embedding))
                .addValue("model", model)
                .addValue("sourceHash", sourceHash)
                .addValue("now", now));
    }

    // pgvector 입력 형식 "[0.1,0.2,...]"
    static String toVectorLiteral(float[] embedding) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(embedding[i]);
        }
        return literal.append(']').toString();
    }
}
