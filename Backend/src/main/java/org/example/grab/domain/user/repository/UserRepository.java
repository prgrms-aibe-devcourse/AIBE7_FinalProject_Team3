package org.example.grab.domain.user.repository;

import org.example.grab.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// 이메일은 호출자가 정규화한 값을 받는다. 대소문자 무시 조회로 규칙을 이중 적용하지 않는다.
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
