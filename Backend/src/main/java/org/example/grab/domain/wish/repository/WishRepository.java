package org.example.grab.domain.wish.repository;

import org.example.grab.domain.wish.entity.Wish;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WishRepository extends JpaRepository<Wish, Long> {

    Optional<Wish> findByUserIdAndDropId(Long userId, Long dropId);
}
