package org.example.grab.domain.drop.repository;

import org.example.grab.domain.drop.dto.SellerDropListProjection;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
            SELECT d.id AS dropId, d.name AS name, d.status AS status,
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
}
