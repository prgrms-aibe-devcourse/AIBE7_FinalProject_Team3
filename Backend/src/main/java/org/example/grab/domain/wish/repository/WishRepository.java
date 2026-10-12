package org.example.grab.domain.wish.repository;

import jakarta.persistence.LockModeType;
import org.example.grab.domain.wish.dto.WishListProjection;
import org.example.grab.domain.wish.dto.WishMailRecipientProjection;
import org.example.grab.domain.wish.entity.Wish;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WishRepository extends JpaRepository<Wish, Long> {

    Optional<Wish> findByUserIdAndDropId(Long userId, Long dropId);

    /*
     * WISH 등록·취소의 쓰기 경로에서 행을 잠그고 조회한다(GR-64 R07).
     * 취소된 행을 두 요청이 동시에 읽으면 각자의 시각으로 activated_at을 덮어쓰므로, 같은 행 잠금으로 직렬화한다.
     * 행이 없으면 잠글 대상이 없어 INSERT 경합 경로(유니크 위반 → 재조회)를 그대로 탄다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wish w WHERE w.userId = :userId AND w.dropId = :dropId")
    Optional<Wish> findByUserIdAndDropIdForUpdate(@Param("userId") Long userId, @Param("dropId") Long dropId);

    // 공개 DROP 상세·목록의 활성 WISH 수. GR-54의 목록·집계도 이 메서드를 재사용한다.
    long countByDropIdAndCanceledAtIsNull(Long dropId);

    /*
     * 내 WISH 목록(WISH-005, MY-003). 활성 WISH(canceled_at IS NULL)만 최신 등록순으로 페이지 조회한다.
     * 목록에 필요한 DROP 필드(이름·현재 상태·썸네일·최저가)를 한 쿼리의 서브쿼리로 계산해 N+1을 막으므로 네이티브로 작성한다.
     * 썸네일은 이미지 sort_order 최솟값, 최저가는 활성 SKU의 unit_price 최솟값이며, 없으면 각각 NULL이다(공개 DROP 목록과 같은 규칙).
     * 정렬은 activated_at DESC, id DESC로 동점에서도 순서가 안정적이다(idx_wishes_user_activated와 정합).
     * countQuery도 활성 WISH만 센다. select 식은 인터페이스 프로젝션(WishListProjection)으로 매핑한다.
     */
    @Query(value = """
            SELECT w.drop_id AS "dropId", d.name AS "name", d.status AS "status",
                   (SELECT i.image_url FROM drop_images i WHERE i.drop_id = d.id
                     ORDER BY i.sort_order LIMIT 1) AS "thumbnailUrl",
                   (SELECT MIN(o.unit_price) FROM drop_options o
                     WHERE o.drop_id = d.id AND o.is_active) AS "minPrice",
                   w.activated_at AS "wishedAt"
            FROM wishes w
            JOIN drops d ON d.id = w.drop_id
            WHERE w.user_id = :userId AND w.canceled_at IS NULL
            ORDER BY w.activated_at DESC, w.id DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM wishes w
            WHERE w.user_id = :userId AND w.canceled_at IS NULL
            """,
            nativeQuery = true)
    Page<WishListProjection> findActiveWishes(@Param("userId") Long userId, Pageable pageable);

    /*
     * 판매 시작 메일 수신자(GR-69). 활성 WISH(canceled_at IS NULL)를 가진 ACTIVE 회원만 대상이다.
     * 전환 배치가 여러 DROP을 한 번에 넘기므로 drop_id IN으로 한 번에 조회해 DROP 수만큼의 왕복을 없앤다.
     * 메일 제목·본문에 DROP 이름이 들어가 drops를 함께 조인한다(내 WISH 목록과 같은 방식).
     * idx_wishes_drop_active (drop_id, canceled_at, id)를 그대로 타고, 정렬은 DROP 단위로 묶기 위한 것이다.
     * 수신 거부(opt-out) 조건은 이번 범위 밖이다. 스키마 추가가 필요해 별도 이슈로 다룬다.
     */
    @Query(value = """
            SELECT w.drop_id AS "dropId", d.name AS "dropName", u.email AS "email"
            FROM wishes w
            JOIN users u ON u.id = w.user_id
            JOIN drops d ON d.id = w.drop_id
            WHERE w.drop_id IN (:dropIds)
              AND w.canceled_at IS NULL
              AND u.status = 'ACTIVE'
            ORDER BY w.drop_id, w.id
            """,
            nativeQuery = true)
    List<WishMailRecipientProjection> findSaleStartMailRecipients(@Param("dropIds") List<Long> dropIds);
}
