package org.example.grab.domain.seller.repository;

import org.example.grab.domain.seller.entity.Seller;
import org.example.grab.domain.seller.entity.SellerStatus;
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
}
