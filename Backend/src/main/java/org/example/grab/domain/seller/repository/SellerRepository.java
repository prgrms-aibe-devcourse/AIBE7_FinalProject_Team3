package org.example.grab.domain.seller.repository;

import org.example.grab.domain.seller.entity.Seller;
import org.example.grab.domain.seller.entity.SellerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    // 중복 사업자등록번호 판단용. uq_sellers_business_registration_number라 결과는 많아야 한 건
    Optional<Seller> findByBusinessRegistrationNumber(String businessRegistrationNumber);

    // 운영자 신청 심사 목록. idx (status, submitted_at, id) 사용
    Page<Seller> findByStatus(SellerStatus status, Pageable pageable);
}
