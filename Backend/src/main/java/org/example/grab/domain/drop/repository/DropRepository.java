package org.example.grab.domain.drop.repository;

import org.example.grab.domain.drop.dto.PublicDropDetailExtrasProjection;
import org.example.grab.domain.drop.dto.PublicDropListProjection;
import org.example.grab.domain.drop.dto.SellerDropListProjection;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DropRepository extends JpaRepository<Drop, Long> {

    @Query("SELECT COUNT(d) FROM Drop d WHERE d.id IN :dropIds AND d.status <> org.example.grab.domain.drop.entity.DropStatus.DRAFT")
    long countPublicDropsForSubscription(@Param("dropIds") List<Long> dropIds);

    @EntityGraph(attributePaths = "options")
    Optional<Drop> findWithOptionsById(Long id);

    /*
     * 공개 상세(GR-184). DROP과 이미지를 한 쿼리로 가져와 이미지 컬렉션 조회 왕복을 줄인다.
     * 옵션·옵션 그룹은 List라 함께 fetch join하면 MultipleBagFetchException이 나므로 기존 @BatchSize 지연 로딩에 맡긴다.
     */
    @EntityGraph(attributePaths = "images")
    Optional<Drop> findWithImagesById(Long id);

    /*
     * 공개 상세의 브랜드·카테고리·활성 WISH 수(GR-184). 판매자·카테고리·WISH 도메인 service로 따로 조회하던 세 값을
     * 한 쿼리로 모아 요청당 DB 왕복을 줄인다. 공개 목록 쿼리(PUBLIC_DROP_SELECT)와 같이 다른 도메인의 엔티티·repository가 아니라
     * 테이블을 네이티브로 읽는다. 기존 조회와 같은 값을 낸다: 브랜드는 sellers PK, 카테고리는 활성 여부와 무관한 PK,
     * WISH 수는 canceled_at IS NULL인 행 수다. 판매자·카테고리 행이 없으면 빈 결과다(호출자가 DROP_NOT_FOUND로 숨긴다).
     */
    @Query(value = """
            SELECT s.brand_name AS "brandName", c.id AS "categoryId", c.name AS "categoryName",
                   (SELECT COUNT(*) FROM wishes w
                     WHERE w.drop_id = d.id AND w.canceled_at IS NULL) AS "wishCount"
            FROM drops d
            JOIN sellers s ON s.id = d.seller_id
            JOIN categories c ON c.id = d.category_id
            WHERE d.id = :dropId
            """, nativeQuery = true)
    Optional<PublicDropDetailExtrasProjection> findPublicDetailExtras(@Param("dropId") Long dropId);

    /*
     * DROP 행의 쓰기 경로(수정·공개·취소)에서 행을 잠그고 조회한다(GR-18, GR-64 R03).
     * 판정 시각·검증은 이 잠금을 획득한 뒤에 수행해야 한다. 잠금 대기 중에 상태가 바뀔 수 있기 때문이다.
     * 잠금 해제 후 상태를 다시 읽게 되므로, 대기 후 재검증으로 최종 상태를 확정한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Drop d WHERE d.id = :dropId")
    Optional<Drop> findByIdForUpdate(@Param("dropId") Long dropId);

    /*
     * 판매자 DROP 목록 조회.
     * 목록에는 SKU 최저가(minPrice)가 필요한데, DROP마다 옵션을 따로 조회하면 N+1이 생긴다.
     * 그래서 상관 서브쿼리로 각 DROP의 활성 SKU 최저가를 같은 쿼리 안에서 계산해 한 번에 가져온다.
     * 서브쿼리는 활성 SKU가 없으면 MIN 결과가 NULL이 되므로 minPrice는 null이 된다(0이 아님).
     * select 식은 인터페이스 프로젝션(SellerDropListProjection)으로 바로 매핑한다.
     * 썸네일은 GALLERY 중 순서가 가장 앞선 이미지이고 DETAIL은 쓰지 않는다. GALLERY가 없으면 null이다.
     * status가 null이면 조건을 통과시켜 전체를, 값이 있으면 해당 상태만 조회한다.
     */
    @Query(value = """
            SELECT d.id AS dropId, d.name AS name,
                   (SELECT i.imageUrl FROM DropImage i
                    WHERE i.drop = d AND i.imageType = org.example.grab.domain.drop.entity.DropImageType.GALLERY
                      AND i.sortOrder = (SELECT MIN(i2.sortOrder) FROM DropImage i2
                                         WHERE i2.drop = d
                                           AND i2.imageType = org.example.grab.domain.drop.entity.DropImageType.GALLERY)) AS thumbnailUrl,
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
     * 공개 DROP 목록 쿼리의 공통 SELECT·FROM. 날짜 정렬과 인기 정렬 쿼리가 같은 응답 형태를 쓰도록 공유한다.
     * 썸네일(LIMIT 1)·ILIKE ESCAPE·컬럼명 기준 정렬 때문에 네이티브로 작성하고, 목록에 필요한 값
     * (brandName·minPrice·soldOut·availableStock·wishCount·thumbnailUrl)을 한 쿼리 안에서 계산해 N+1을 막는다.
     * 브랜드는 sellers를 PK(seller_id)로 조인해 가져온다. 판매자당 DROP이 여러 건이어도 조인 방향이
     * DROP→판매자라 목록 행이 늘지 않아 다른 집계를 부풀리지 않는다.
     * 활성 WISH 집계(canceled_at IS NULL)는 WishRepository 대신 같은 쿼리 안에서 네이티브로 센다.
     * 썸네일은 GALLERY 중 순서가 가장 앞선 이미지이고 DETAIL은 쓰지 않는다(DROP.md 1.2).
     * 가용 재고 합계식은 soldOut·availableStock SELECT, PUBLIC_DROP_FILTERS, POPULAR_GRAB_WHERE에 중복되므로
     * 한쪽만 바꾸지 않도록 주의한다(활성 SKU 없으면 COALESCE로 0). LATERAL 조인으로 한 번만 계산하면
     * 필터가 없을 때도 후보 전체에 계산이 강제돼 오히려 느려진다(GR-177, k6/drop-list-scale/result.md).
     * 지금 식은 필터가 없으면 건너뛰고 SELECT 쪽은 LIMIT 뒤 행에서만 계산된다.
     */
    String PUBLIC_DROP_SELECT = """
            SELECT d.id AS "dropId", d.name AS "name", s.brand_name AS "brandName",
                   d.status AS "status",
                   d.sale_starts_at AS "saleStartsAt", d.sale_ends_at AS "saleEndsAt",
                   c.id AS "categoryId", c.name AS "categoryName",
                   (SELECT i.image_url FROM drop_images i WHERE i.drop_id = d.id AND i.image_type = 'GALLERY'
                     ORDER BY i.sort_order LIMIT 1) AS "thumbnailUrl",
                   (SELECT MIN(o.unit_price) FROM drop_options o
                     WHERE o.drop_id = d.id AND o.is_active) AS "minPrice",
                   (SELECT COALESCE(SUM(o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity), 0) = 0
                      FROM drop_options o WHERE o.drop_id = d.id AND o.is_active) AS "soldOut",
                   (SELECT COALESCE(SUM(o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity), 0)
                      FROM drop_options o WHERE o.drop_id = d.id AND o.is_active) AS "availableStock",
                   (SELECT COUNT(*) FROM wishes w
                     WHERE w.drop_id = d.id AND w.canceled_at IS NULL) AS "wishCount"
            FROM drops d
            JOIN categories c ON c.id = d.category_id
            JOIN sellers s ON s.id = d.seller_id
            """;

    String PUBLIC_DROP_COUNT = """
            SELECT COUNT(*)
            FROM drops d
            JOIN categories c ON c.id = d.category_id
            """;

    /*
     * categoryId·keyword 필터. WHERE 절 뒤에 AND로 이어 붙인다.
     * PostgreSQL은 네이티브 쿼리의 null 파라미터 타입을 추론하지 못하므로 CAST로 타입을 지정한다.
     */
    String CATEGORY_KEYWORD_FILTERS = """
              AND (CAST(:categoryId AS BIGINT) IS NULL OR d.category_id = :categoryId)
              AND (CAST(:keyword AS TEXT) IS NULL OR d.name ILIKE :keyword ESCAPE '\\')
            """;

    // categoryId·keyword·soldOut 필터. 인기 GRAB은 후보가 이미 가용 재고 > 0이라 soldOut 필터를 붙이지 않는다(GR-177).
    String PUBLIC_DROP_FILTERS = CATEGORY_KEYWORD_FILTERS + """
              AND (CAST(:soldOut AS BOOLEAN) IS NULL
                   OR (SELECT COALESCE(SUM(o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity), 0) = 0
                         FROM drop_options o WHERE o.drop_id = d.id AND o.is_active) = :soldOut)
            """;

    // 인기 WISH 후보: 저장 상태 WISH이면서 서버 시각상 아직 판매 전인 상품(DROP.md 1.2).
    String POPULAR_WISH_WHERE = """
            WHERE d.status = 'WISH'
              AND d.sale_starts_at > :now
            """;

    /*
     * 인기 GRAB 후보: 저장 상태 WISH·GRAB 중 서버 시각상 판매 기간 안이고 활성 SKU 가용 재고가 1개 이상인 상품(DROP.md 1.2).
     * 시작 배치가 늦어 아직 WISH로 저장된 상품도 판매 기간이면 포함한다. 재고 판정식은 soldOut 판정식의 부정이다.
     */
    String POPULAR_GRAB_WHERE = """
            WHERE d.status IN ('WISH', 'GRAB')
              AND d.sale_starts_at <= :now
              AND d.sale_ends_at > :now
              AND (SELECT COALESCE(SUM(o.total_quantity - o.reserved_quantity - o.sold_quantity - o.withheld_quantity), 0)
                     FROM drop_options o WHERE o.drop_id = d.id AND o.is_active) > 0
            """;

    // 관련 DROP 추천(DROP.md 1.4)의 카드. 목록 항목과 같은 형식이며 순서는 호출하는 쪽이 정한다
    @Query(value = PUBLIC_DROP_SELECT + """
            WHERE d.id IN (:ids)
            """, nativeQuery = true)
    List<PublicDropListProjection> findPublicDropsByIds(@Param("ids") List<Long> ids);

    /*
     * 공개 DROP 목록 조회(DISC-001·002·003, STOCK-006). 날짜 정렬은 Pageable Sort로 받는다.
     * statuses는 서비스가 채워 넘기며 DRAFT·CANCELED는 들어오지 않는다. 카테고리는 INNER JOIN이다.
     */
    @Query(value = PUBLIC_DROP_SELECT + """
            WHERE d.status IN (:statuses)
            """ + PUBLIC_DROP_FILTERS,
            countQuery = PUBLIC_DROP_COUNT + """
            WHERE d.status IN (:statuses)
            """ + PUBLIC_DROP_FILTERS,
            nativeQuery = true)
    Page<PublicDropListProjection> findPublicDrops(
            @Param("statuses") List<String> statuses,
            @Param("categoryId") Long categoryId,
            @Param("keyword") String keyword,
            @Param("soldOut") Boolean soldOut,
            Pageable pageable);

    /*
     * 인기 WISH 목록(DISC-007). 활성 WISH 수 내림차순, 동률은 published_at DESC, id DESC로 고정한다.
     * 집계값 정렬은 Pageable Sort로 표현할 수 없어 ORDER BY를 쿼리에 두고, pageable은 정렬 없이 페이지만 넘긴다.
     * 전체 후보를 DB에서 정렬한 뒤 LIMIT/OFFSET으로 자르므로 페이지가 달라도 전역 순위가 유지된다.
     * now는 서비스가 조회마다 한 번 고정해 넘기며, 상태 전환 배치 지연과 무관하게 판매 전 여부를 판정한다.
     */
    @Query(value = PUBLIC_DROP_SELECT + POPULAR_WISH_WHERE + PUBLIC_DROP_FILTERS + """
            ORDER BY "wishCount" DESC, d.published_at DESC, d.id DESC
            """,
            countQuery = PUBLIC_DROP_COUNT + POPULAR_WISH_WHERE + PUBLIC_DROP_FILTERS,
            nativeQuery = true)
    Page<PublicDropListProjection> findPopularWishDrops(
            @Param("categoryId") Long categoryId,
            @Param("keyword") String keyword,
            @Param("soldOut") Boolean soldOut,
            @Param("now") OffsetDateTime now,
            Pageable pageable);

    /*
     * 인기 GRAB 목록(DISC-007). 판매 수량 내림차순, 동률은 published_at DESC, id DESC로 고정한다.
     * 판매 수량은 결제 확정·미취소 주문(paid_at IS NOT NULL AND canceled_at IS NULL)의 order_items.quantity 합이다.
     * 미결제·만료·취소 주문과 결제 시도 횟수는 세지 않는다. 이 합은 DROP의 모든 SKU(비활성 포함) sold_quantity 합과 같다.
     * sold_quantity는 결제 확정(markPaid와 같은 트랜잭션에서 reserved → sold)과 결제 후 취소(cancel과 같은 트랜잭션에서
     * sold 감소)에서만 바뀌기 때문이다(ERD.md 3.2·3.3). 그래서 후보마다 orders·order_items를 집계하지 않고
     * 이미 읽는 drop_options에서 합을 구한다(GR-176). 재고 원장을 바꾸는 경로가 늘면 이 동등성을 다시 확인한다.
     * 정렬·페이징·now 처리는 findPopularWishDrops와 같다.
     * soldOut 필터는 받지 않는다. 후보 조건(가용 재고 > 0)과 같은 합계를 후보마다 한 번 더 계산할 뿐이라
     * 서비스가 soldOut=true는 빈 목록으로, false는 필터 없이 처리한다(DROP.md 1.2, GR-177).
     */
    @Query(value = PUBLIC_DROP_SELECT + POPULAR_GRAB_WHERE + CATEGORY_KEYWORD_FILTERS + """
            ORDER BY (SELECT COALESCE(SUM(o.sold_quantity), 0)
                        FROM drop_options o
                       WHERE o.drop_id = d.id) DESC,
                     d.published_at DESC, d.id DESC
            """,
            countQuery = PUBLIC_DROP_COUNT + POPULAR_GRAB_WHERE + CATEGORY_KEYWORD_FILTERS,
            nativeQuery = true)
    Page<PublicDropListProjection> findPopularGrabDrops(
            @Param("categoryId") Long categoryId,
            @Param("keyword") String keyword,
            @Param("now") OffsetDateTime now,
            Pageable pageable);

    /*
     * 시작 배치(GR-18). 대상을 잠가 ID를 확보한 뒤 같은 트랜잭션에서 전환하고, 전환된 ID 목록을 반환한다.
     * 한 문장 UPDATE는 건수만 알려주는데, 판매 시작 알림(GR-69)에는 실제로 전환된 DROP의 ID가 필요해 두 쿼리로 나눴다.
     * UPDATE ... RETURNING id는 @Modifying 규약 밖이라 쓰지 않는다.
     * 두 쿼리는 호출자의 한 트랜잭션 안에서 실행해야 한다. 잠금이 풀리면 확보한 ID가 전환 대상이라는 보장이 사라진다.
     * 반환 건수로 호출자가 다음 배치 실행 여부를 판단하는 것은 이전과 같다.
     */
    default List<Long> startGrabBatch(OffsetDateTime now, int batchSize) {
        List<Long> ids = lockStartGrabTargets(now, batchSize);
        if (ids.isEmpty()) {
            return ids;
        }
        markGrabStarted(ids, now);
        return ids;
    }

    /*
     * 시작 배치 1단계. 후보 행을 잠그고 ID만 확보한다.
     * FOR UPDATE SKIP LOCKED가 다른 인스턴스가 잡은 행을 건너뛰고, 잠금을 얻은 뒤 조건(status·sale_starts_at)이
     * 다시 평가되므로 반환된 ID는 이 트랜잭션이 전환을 확정할 대상이다. 여러 인스턴스가 중복 실행해도 멱등하다.
     * ORDER BY는 인덱스(idx_drops_sale_start) 정렬과 맞춰 잠금 순서를 안정시킨다.
     */
    @Query(value = """
            SELECT id FROM drops
            WHERE status = 'WISH' AND sale_starts_at <= :now
            ORDER BY sale_starts_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockStartGrabTargets(@Param("now") OffsetDateTime now, @Param("batchSize") int batchSize);

    /*
     * 시작 배치 2단계. 1단계에서 잠근 행만 바꾼다.
     * 잠근 행은 다른 트랜잭션이 바꿀 수 없으므로 상태 조건을 다시 걸지 않는다.
     * 엔티티 로딩·dirty checking을 거치지 않으므로 updated_at을 직접 갱신한다.
     */
    @Modifying
    @Query(value = """
            UPDATE drops SET status = 'GRAB', grab_started_at = :now, updated_at = :now
            WHERE id IN (:ids)
            """, nativeQuery = true)
    int markGrabStarted(@Param("ids") List<Long> ids, @Param("now") OffsetDateTime now);

    /*
     * 판매 시작 10분 전 알림 대상 선점(GR-69).
     * 판매 시작 알림과 달리 DROP 상태가 바뀌지 않아 멱등 토큰이 없다. "10분 전 범위"는 스케줄러 주기마다
     * 계속 참이므로, 기록을 남기지 않으면 같은 사람에게 주기마다 메일이 나간다.
     * drop_notifications의 UNIQUE (drop_id, type)이 그 토큰이다. 여러 인스턴스가 같은 DROP을 동시에 집어도
     * INSERT에 성공하는 쪽은 하나뿐이고, RETURNING이 그 행만 돌려주므로 발송도 한 번만 일어난다.
     * 행 잠금(FOR UPDATE SKIP LOCKED)은 쓰지 않는다. 잠금은 drops 행을 직렬화할 뿐이고, 다른 트랜잭션이
     * 이미 기록을 커밋했는지는 조회 스냅샷 시점에 따라 놓칠 수 있다. 유니크 제약만이 유일한 보증이다.
     * 선점과 ID 확보가 한 문장이라 결과를 받는 쿼리로 선언한다(@Modifying은 결과를 돌려주지 못한다).
     * 이미 판매가 시작된 DROP(sale_starts_at <= now)은 제외한다. 그 구간은 판매 시작 알림이 담당한다.
     *
     * 이미 기록이 있는 DROP은 NOT EXISTS로 후보에서 먼저 뺀다. LIMIT이 ON CONFLICT보다 먼저 적용되므로,
     * 거르지 않으면 batchSize만큼의 기선점 DROP이 매 주기 앞자리를 차지해 그 뒤 DROP은 영영 선점되지 않고
     * 배치 반복(DropTransitionService.drain)도 0건을 받아 멈춘다. uq_drop_notifications를 그대로 탄다.
     * NOT EXISTS는 조회 스냅샷 기준이라 동시 실행을 막지 못하므로, 중복 방지는 ON CONFLICT가 계속 맡는다.
     */
    @Query(value = """
            INSERT INTO drop_notifications (drop_id, type, sent_at)
            SELECT d.id, :type, :now FROM drops d
            WHERE d.status = 'WISH'
              AND d.sale_starts_at > :now
              AND d.sale_starts_at <= :noticeUntil
              AND NOT EXISTS (
                  SELECT 1 FROM drop_notifications n
                  WHERE n.drop_id = d.id AND n.type = :type
              )
            ORDER BY d.sale_starts_at, d.id
            LIMIT :batchSize
            ON CONFLICT (drop_id, type) DO NOTHING
            RETURNING drop_id
            """, nativeQuery = true)
    List<Long> claimNoticeBatch(
            @Param("type") String type,
            @Param("now") OffsetDateTime now,
            @Param("noticeUntil") OffsetDateTime noticeUntil,
            @Param("batchSize") int batchSize);

    /*
     * 판매 종료 배치(GR-18). startGrabBatch와 잠금·멱등 규칙이 같다.
     * 종료 사유는 TIME_EXPIRED로 고정한다(SOLD_OUT 조기 종료는 범위 밖).
     */
    default int endGrabBatch(OffsetDateTime now, int batchSize) {
        return endGrabBatchIds(now, batchSize).size();
    }

    default List<Long> endGrabBatchIds(OffsetDateTime now, int batchSize) {
        List<Long> ids = lockEndGrabTargets(now, batchSize);
        if (!ids.isEmpty()) {
            markGrabEnded(ids, now);
        }
        return ids;
    }

    @Query(value = """
            SELECT id FROM drops
            WHERE status = 'GRAB' AND sale_ends_at <= :now
            ORDER BY sale_ends_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockEndGrabTargets(@Param("now") OffsetDateTime now, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            UPDATE drops SET status = 'ENDED', closed_at = :now, close_reason = 'TIME_EXPIRED', updated_at = :now
            WHERE id IN (:ids)
            """, nativeQuery = true)
    int markGrabEnded(@Param("ids") List<Long> ids, @Param("now") OffsetDateTime now);
}
