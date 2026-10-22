package org.example.grab.domain.user.repository;

import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

// 이메일은 호출자가 정규화한 값을 받는다. 대소문자 무시 조회로 규칙을 이중 적용하지 않는다.
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    // 소셜 콜백의 기존 회원 조회(MEMBER_AUTH.md 1.5 판정 1). 이메일이 아니라 제공자 식별자로 찾는다(GR-46 M00-05)
    Optional<User> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

    boolean existsByEmail(String email);

    /*
        닉네임 중복은 대소문자를 구분하지 않는다(MEMBER_AUTH.md 1.2.3).
        IgnoreCase 파생 쿼리는 upper()로 비교하므로, uq_users_nickname_lower와 같은 lower() 식을 직접 쓴다.
        동시 가입은 이 검사를 함께 통과할 수 있어 최종 판단은 유니크 인덱스가 한다.
     */
    @Query("select count(u) > 0 from User u where lower(u.nickname) = lower(:nickname)")
    boolean existsByNicknameIgnoreCase(@Param("nickname") String nickname);

    /*
        내 닉네임 수정의 중복 검사(MEMBER_AUTH.md 1.10, GR-129 M00-05). 비교 식은 existsByNicknameIgnoreCase와 같고 본인 행만 뺀다.
        본인 현재 닉네임과 대소문자만 다른 값으로 바꾸는 것은 중복이 아니다. 유니크 인덱스도 본인 행 갱신이라 걸리지 않는다.
     */
    @Query("select count(u) > 0 from User u where lower(u.nickname) = lower(:nickname) and u.id <> :userId")
    boolean existsByNicknameIgnoreCaseExcludingUser(@Param("nickname") String nickname, @Param("userId") Long userId);

    /*
        내 비밀번호 변경의 저장 단계(MEMBER_AUTH.md 1.11, GR-129 M03-02). 현재 비밀번호 검증(Argon2)은 트랜잭션 밖에서 하므로,
        그 사이 다른 요청이 비밀번호를 바꿨을 수 있다. 검증할 때 읽은 해시가 그대로일 때만 바꾸고, 바꾼 행 수를 돌려준다.
        0이면 요청의 현재 비밀번호가 더 이상 맞지 않는 것이라 호출자가 PASSWORD_MISMATCH로 응답한다.
        - 엔티티 메서드 대신 조건부 UPDATE를 쓰는 예외다(M03-01 A안). 읽은 값과 비교해 바꾸는 일이 한 문장이라 행 잠금이 필요 없다
        - LOCAL 회원만 비밀번호가 있으므로 provider 조건을 함께 건다. 회원 상태는 조회 때 확인했고 여기서 다시 보지 않는다(M03-02)
        - 엔티티 로딩·dirty checking을 거치지 않으므로 updated_at을 직접 갱신한다(OrderInventoryRepository와 같음)
        - 선언한 쿼리 메서드에는 트랜잭션이 붙지 않고 @Modifying은 트랜잭션이 있어야 하므로 여기서 연다.
          호출하는 서비스는 Argon2 동안 DB 연결을 잡지 않으려고 트랜잭션을 두지 않는다(M03-02)
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE users SET password_hash = :newHash, updated_at = CURRENT_TIMESTAMP
            WHERE id = :userId AND provider = 'LOCAL' AND password_hash = :readHash
            """, nativeQuery = true)
    int updatePasswordHashIfUnchanged(@Param("userId") Long userId,
                                      @Param("readHash") String readHash,
                                      @Param("newHash") String newHash);

    @Query("select u.id from User u where u.uuid = :publicId")
    Optional<Long> findIdByPublicId(@Param("publicId") UUID publicId);
}
