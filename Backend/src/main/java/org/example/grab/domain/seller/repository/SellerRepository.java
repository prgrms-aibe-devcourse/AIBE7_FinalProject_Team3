package org.example.grab.domain.seller.repository;

import jakarta.persistence.LockModeType;
import org.example.grab.domain.seller.dto.AdminSellerApplicationResponse;
import org.example.grab.domain.seller.entity.Seller;
import org.example.grab.domain.seller.entity.SellerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SellerRepository extends JpaRepository<Seller, Long> {

    // Seller는 users를 ID로만 가지므로 엔티티 조인(on)으로 한 번에 조회한다. sellers.user_id가 UNIQUE라 결과는 많아야 한 건
    @Query("""
            select s.id from Seller s
            join User u on u.id = s.userId
            where u.uuid = :userPublicId and s.status = :status
            """)
    Optional<Long> findIdByUserPublicIdAndStatus(@Param("userPublicId") UUID userPublicId,
                                                 @Param("status") SellerStatus status);

    // 신청·재신청·중복 신청 판단용. uq_sellers_user라 결과는 많아야 한 건
    Optional<Seller> findByUserId(Long userId);

    // 공개 DROP 상세 등에서 브랜드명만 필요할 때 쓴다. sellers.id는 PK라 결과는 많아야 한 건
    @Query("select s.brandName from Seller s where s.id = :sellerId")
    Optional<String> findBrandNameById(@Param("sellerId") Long sellerId);

    // 중복 사업자등록번호 판단용. uq_sellers_business_registration_number라 결과는 많아야 한 건
    Optional<Seller> findByBusinessRegistrationNumber(String businessRegistrationNumber);

    // 운영자 신청 심사 목록. idx (status, submitted_at, id) 사용
    Page<Seller> findByStatus(SellerStatus status, Pageable pageable);

    // 서류 뷰어 등 읽기 전용 단건 조회. applicationId는 외부 노출용 uuid(public_id)
    Optional<Seller> findByUuid(UUID uuid);

    // 승인/반려 대상 조회(GR-92). 동시에 같은 신청을 승인·반려하는 요청을 직렬화해, 상태 확인과 전이 사이에 다른 요청이 끼지 못하게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seller s where s.uuid = :uuid")
    Optional<Seller> findByUuidForUpdate(@Param("uuid") UUID uuid);

    // 운영자 심사 목록(status 미지정 시 전체). 응답에 신청자의 public_id가 필요해 User와 조인한다
    @Query(value = """
            select new org.example.grab.domain.seller.dto.AdminSellerApplicationResponse(
                s.uuid, u.uuid, s.brandName, s.contactEmail, s.businessRegistrationNumber, s.status, s.submittedAt)
            from Seller s
            join User u on u.id = s.userId
            where (:status is null or s.status = :status)
            order by s.submittedAt asc, s.id asc
            """,
            countQuery = """
            select count(s) from Seller s
            where (:status is null or s.status = :status)
            """)
    Page<AdminSellerApplicationResponse> findApplications(@Param("status") SellerStatus status, Pageable pageable);
}
