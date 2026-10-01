package org.example.grab.domain.drop.repository;

import org.example.grab.domain.drop.dto.PublicDropListProjection;
import org.example.grab.domain.drop.dto.SellerDropListProjection;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DropRepository extends JpaRepository<Drop, Long> {

    @EntityGraph(attributePaths = "options")
    Optional<Drop> findWithOptionsById(Long id);

    /*
     * 판매자 DROP 목록 조회.
     * 목록에는 SKU 최저가(minPrice)가 필요한데, DROP마다 옵션을 따로 조회하면 N+1이 생긴다.
     * 그래서 상관 서브쿼리로 각 DROP의 활성 SKU 최저가를 같은 쿼리 안에서 계산해 한 번에 가져온다.
     * 서브쿼리는 활성 SKU가 없으면 MIN 결과가 NULL이 되므로 minPrice는 null이 된다(0이 아님).
     * select 식은 인터페이스 프로젝션(SellerDropListProjection)으로 바로 매핑한다.
     * status가 null이면 조건을 통과시켜 전체를, 값이 있으면 해당 상태만 조회한다.
     */
    @Query(value = """
            SELECT d.id AS dropId, d.name AS name,
                   (SELECT i.imageUrl FROM DropImage i
                    WHERE i.drop = d AND i.sortOrder = (SELECT MIN(i2.sortOrder) FROM DropImage i2 WHERE i2.drop = d)) AS thumbnailUrl,
                   d.status AS status,
                   (SELECT MIN(o.unitPrice) FROM DropOption o WHERE o.drop = d AND o.active = true) AS minPrice,
                   d.createdAt AS createdAt
            FROM Drop d
            WHERE d.sellerId = :sellerId AND (:status IS NULL OR d.status = :status)
            ORDER BY d.id DESC
            """,
            countQuery = """
            SELECT COUNT(d)
            FROM Drop d
            WHERE d.sellerId = :sellerId AND (:status IS NULL OR d.status = :status)
            """)
    Page<SellerDropListProjection> findSellerDrops(
            @Param("sellerId") Long sellerId,
            @Param("status") DropStatus status,
            Pageable pageable);

    /*
     * 공개 DROP 목록 조회(DISC-001·002·003, STOCK-006).
     * 썸네일(LIMIT 1)·ILIKE ESCAPE·컬럼명 기준 정렬 때문에 네이티브로 작성하고, 목록에 필요한 값
     * (minPrice·soldOut·wishCount·thumbnailUrl)을 한 쿼리 안에서 서브쿼리로 계산해 N+1을 막는다.
     * 활성 WISH 집계(canceled_at IS NULL)는 WishRepository 대신 같은 쿼리 안에서 네이티브로 센다.
     * PostgreSQL은 네이티브 쿼리의 null 파라미터 타입을 추론하지 못하므로 CAST로 타입을 지정한다.
     * soldOut 판정식은 SELECT와 WHERE 두 곳에 중복되므로 한쪽만 바꾸지 않도록 주의한다.
     * statuses는 서비스가 채워 넘기며 DRAFT·CANCELED는 들어오지 않는다. 카테고리는 INNER JOIN이다.
     */
    @Query(value = """
            SELECT d.id AS "dropId", d.name AS "name", d.status AS "status",
                   d.sale_starts_at AS "saleStartsAt", d.sale_ends_at AS "saleEndsAt",
                   c.id AS "categoryId", c.name AS "categoryName",
                   (SELECT i.image_url FROM drop_images i WHERE i.drop_id = d.id
                     ORDER BY i.sort_order LIMIT 1) AS "thumbnailUrl",
                   (SELECT MIN(o.unit_price) FROM drop_options o
                     WHERE o.drop_id = d.id AND o.is_active) AS "minPrice",
                   (SELECT COALESCE(SUM(o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity), 0) = 0
                      FROM drop_options o WHERE o.drop_id = d.id AND o.is_active) AS "soldOut",
                   (SELECT COUNT(*) FROM wishes w
                     WHERE w.drop_id = d.id AND w.canceled_at IS NULL) AS "wishCount"
            FROM drops d
            JOIN categories c ON c.id = d.category_id
            WHERE d.status IN (:statuses)
              AND (CAST(:categoryId AS BIGINT) IS NULL OR d.category_id = :categoryId)
              AND (CAST(:keyword AS TEXT) IS NULL OR d.name ILIKE :keyword ESCAPE '\\')
              AND (CAST(:soldOut AS BOOLEAN) IS NULL
                   OR (SELECT COALESCE(SUM(o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity), 0) = 0
                         FROM drop_options o WHERE o.drop_id = d.id AND o.is_active) = :soldOut)
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM drops d
            JOIN categories c ON c.id = d.category_id
            WHERE d.status IN (:statuses)
              AND (CAST(:categoryId AS BIGINT) IS NULL OR d.category_id = :categoryId)
              AND (CAST(:keyword AS TEXT) IS NULL OR d.name ILIKE :keyword ESCAPE '\\')
              AND (CAST(:soldOut AS BOOLEAN) IS NULL
                   OR (SELECT COALESCE(SUM(o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity), 0) = 0
                         FROM drop_options o WHERE o.drop_id = d.id AND o.is_active) = :soldOut)
            """,
            nativeQuery = true)
    Page<PublicDropListProjection> findPublicDrops(
            @Param("statuses") List<String> statuses,
            @Param("categoryId") Long categoryId,
            @Param("keyword") String keyword,
            @Param("soldOut") Boolean soldOut,
            Pageable pageable);

    /*
     * 상태 전환 배치(GR-18). 후보 잠금·상태 조건 재검증·변경을 한 문장으로 원자 처리한다.
     * 서브쿼리의 FOR UPDATE SKIP LOCKED가 후보 행을 잠그고, 바깥 UPDATE가 같은 트랜잭션에서 값을 바꾼다.
     * 조건(status·sale_starts_at)이 다시 평가되므로 여러 인스턴스가 중복 실행해도 멱등하다.
     * 엔티티 로딩·dirty checking을 거치지 않으므로 updated_at을 직접 갱신한다.
     * ORDER BY는 인덱스(idx_drops_sale_start) 정렬과 맞춰 잠금 순서를 안정시킨다.
     * 변경 건수를 반환해 호출자가 다음 배치 실행 여부를 판단한다.
     */
    @Modifying
    @Query(value = """
            UPDATE drops SET status = 'GRAB', grab_started_at = :now, updated_at = :now
            WHERE id IN (
                SELECT id FROM drops
                WHERE status = 'WISH' AND sale_starts_at <= :now
                ORDER BY sale_starts_at, id
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            """, nativeQuery = true)
    int startGrabBatch(@Param("now") OffsetDateTime now, @Param("batchSize") int batchSize);

    /*
     * 판매 종료 배치(GR-18). startGrabBatch와 잠금·멱등 규칙이 같다.
     * 종료 사유는 TIME_EXPIRED로 고정한다(SOLD_OUT 조기 종료는 범위 밖).
     */
    @Modifying
    @Query(value = """
            UPDATE drops SET status = 'ENDED', closed_at = :now, close_reason = 'TIME_EXPIRED', updated_at = :now
            WHERE id IN (
                SELECT id FROM drops
                WHERE status = 'GRAB' AND sale_ends_at <= :now
                ORDER BY sale_ends_at, id
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            """, nativeQuery = true)
    int endGrabBatch(@Param("now") OffsetDateTime now, @Param("batchSize") int batchSize);
}
