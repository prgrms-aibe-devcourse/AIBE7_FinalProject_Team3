package org.example.grab.domain.drop.repository;

import org.example.grab.domain.drop.entity.DropImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface DropImageRepository extends JpaRepository<DropImage, Long> {

    // 다른 DROP이 이미 쓰는 imageId인지 확인한다. dropId가 null(새 DRAFT)이면 어떤 DROP이든 사용 중이면 true다.
    @Query("select count(i) > 0 from DropImage i where i.uuid = :uuid and (:dropId is null or i.drop.id <> :dropId)")
    boolean existsByUuidUsedByOtherDrop(@Param("uuid") UUID uuid, @Param("dropId") Long dropId);
}
