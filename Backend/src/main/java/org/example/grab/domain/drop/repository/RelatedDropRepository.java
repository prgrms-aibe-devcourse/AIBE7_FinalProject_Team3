package org.example.grab.domain.drop.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/*
    관련 DROP 후보 선택(DROP.md 1.4). 후보는 저장 상태 WISH이면서 아직 판매 전인 DROP이며 대상 DROP은 뺀다.
    겹친 사용자 수·유사도는 정렬에만 쓰고 돌려주지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class RelatedDropRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /*
        함께 WISH: 대상 DROP을 WISH한(활성) 사용자가 함께 WISH한(활성) 후보를 겹친 사용자 수가 많은 순으로 고른다.
        minCoWishers 미만으로 겹친 후보는 뺀다. 특정 사용자의 WISH 내역을 짐작하지 못하게 하려는 최소 단위다.
     */
    public List<Long> findCoWishedDropIds(long dropId, OffsetDateTime now, int minCoWishers, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT d.id
                FROM wishes target
                JOIN wishes other ON other.user_id = target.user_id AND other.drop_id <> target.drop_id
                JOIN drops d ON d.id = other.drop_id
                WHERE target.drop_id = :dropId
                  AND target.canceled_at IS NULL
                  AND other.canceled_at IS NULL
                  AND d.status = 'WISH'
                  AND d.sale_starts_at > :now
                GROUP BY d.id, d.sale_starts_at
                HAVING COUNT(DISTINCT other.user_id) >= :minCoWishers
                ORDER BY COUNT(DISTINCT other.user_id) DESC, d.sale_starts_at, d.id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("dropId", dropId)
                .addValue("now", now)
                .addValue("minCoWishers", minCoWishers)
                .addValue("limit", limit), Long.class);
    }

    /*
        비슷한 DROP: 대상과 같은 카테고리이며 임베딩 코사인 거리가 maxDistance(1 - 유사도 기준) 이하인 후보를 가까운 순으로 고른다.
        대상이나 후보에 임베딩이 없으면 고르지 않는다.
     */
    public List<Long> findSimilarDropIds(long dropId, OffsetDateTime now, double maxDistance, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT d.id
                FROM drops target
                JOIN drop_embeddings target_embedding ON target_embedding.drop_id = target.id
                JOIN drops d ON d.category_id = target.category_id AND d.id <> target.id
                JOIN drop_embeddings e ON e.drop_id = d.id
                WHERE target.id = :dropId
                  AND d.status = 'WISH'
                  AND d.sale_starts_at > :now
                  AND (e.embedding <=> target_embedding.embedding) <= :maxDistance
                ORDER BY e.embedding <=> target_embedding.embedding, d.id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("dropId", dropId)
                .addValue("now", now)
                .addValue("maxDistance", maxDistance)
                .addValue("limit", limit), Long.class);
    }
}
