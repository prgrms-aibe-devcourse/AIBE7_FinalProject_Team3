package org.example.grab.domain.drop.repository;

import org.example.grab.domain.drop.entity.DropImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;
import java.util.UUID;

public interface DropImageRepository extends JpaRepository<DropImage, Long> {

    // 다른 DROP이 이미 쓰는 imageId를 한 번에 조회한다. dropId가 null(새 DRAFT)이면 사용 중인 값을 모두 반환한다.
    @Query("select i.uuid from DropImage i where i.uuid in :uuids and (:dropId is null or i.drop.id <> :dropId)")
    Set<UUID> findUuidsUsedByOtherDrop(@Param("uuids") Set<UUID> uuids, @Param("dropId") Long dropId);
}
