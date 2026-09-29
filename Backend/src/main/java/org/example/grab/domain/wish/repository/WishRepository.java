package org.example.grab.domain.wish.repository;

import org.example.grab.domain.wish.entity.Wish;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WishRepository extends JpaRepository<Wish, Long> {

    Optional<Wish> findByUserIdAndDropId(Long userId, Long dropId);

    // 공개 DROP 상세·목록의 활성 WISH 수. GR-54의 목록·집계도 이 메서드를 재사용한다.
    long countByDropIdAndCanceledAtIsNull(Long dropId);
}
