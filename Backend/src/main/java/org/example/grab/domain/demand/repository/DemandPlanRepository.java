package org.example.grab.domain.demand.repository;

import jakarta.persistence.LockModeType;
import org.example.grab.domain.demand.entity.DemandPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DemandPlanRepository extends JpaRepository<DemandPlan, Long> {

    Optional<DemandPlan> findByUuid(UUID uuid);

    // 같은 계획에 결정이 동시에 기록되지 않도록 행을 잠근 뒤 상태를 다시 확인한다(ERD.md 1.7)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DemandPlan p where p.uuid = :uuid")
    Optional<DemandPlan> findByUuidForUpdate(@Param("uuid") UUID uuid);

    @Query("select p from DemandPlan p where p.dropId = :dropId order by p.createdAt desc, p.id desc")
    Page<DemandPlan> findByDropIdLatestFirst(@Param("dropId") Long dropId, Pageable pageable);
}
