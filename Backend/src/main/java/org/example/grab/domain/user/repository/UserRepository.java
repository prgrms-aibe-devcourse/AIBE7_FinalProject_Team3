package org.example.grab.domain.user.repository;

import org.example.grab.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

// 이메일은 호출자가 정규화한 값을 받는다. 대소문자 무시 조회로 규칙을 이중 적용하지 않는다.
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /*
        닉네임 중복은 대소문자를 구분하지 않는다(MEMBER_AUTH.md 1.2.3).
        IgnoreCase 파생 쿼리는 upper()로 비교하므로, uq_users_nickname_lower와 같은 lower() 식을 직접 쓴다.
        동시 가입은 이 검사를 함께 통과할 수 있어 최종 판단은 유니크 인덱스가 한다.
     */
    @Query("select count(u) > 0 from User u where lower(u.nickname) = lower(:nickname)")
    boolean existsByNicknameIgnoreCase(@Param("nickname") String nickname);

    @Query("select u.id from User u where u.uuid = :publicId")
    Optional<Long> findIdByPublicId(@Param("publicId") UUID publicId);
}
