package org.example.grab.domain.wish.repository;

import jakarta.persistence.LockModeType;
import org.example.grab.domain.wish.entity.Wish;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
