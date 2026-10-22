package org.example.grab.domain.user.repository;

import jakarta.persistence.EntityManager;
import org.example.grab.global.config.JpaConfig;
import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserRole;
import org.example.grab.domain.user.entity.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest // JPA 관련 부분만 띄우고 테스트마다 롤백
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // H2 같은 임베디드 DB로 바꾸지 않게 막음
@ImportAutoConfiguration(FlywayAutoConfiguration.class) // 빈 컨테이너에 Flyway 마이그레이션 적용
@Import(JpaConfig.class) // createdAt, updatedAt auditing을 켬
@Testcontainers // 테스트 클래스 시작 시 PostgreSQL 18 컨테이너를 띄우고, 끝나면 정리
class  UserRepositoryTest {

    private static final String PASSWORD_HASH = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";

    // Testcontainers가 관리할 컨테이너
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    // 설정을 컨테이너 주소로 덮어씀
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // JpaConfig 정상 작동하는지 테스트
    @Test
    @DisplayName("회원 저장 시 auditing이 생성·수정 시각을 기록한다")
    void recordsAuditingTimestampsOnSave() {
        // given
        User user = User.createLocal("user@example.com", PASSWORD_HASH, "홍길동");

        // when
        userRepository.save(user);

        // then
        // auditing이 INSERT 전에 값을 채웠는지 확인한다.
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();

        entityManager.flush();
        entityManager.clear();

        User found = userRepository.findById(user.getId()).orElseThrow();

        // id로 다시 조회해서, DB에서 읽은 시각이 저장 전 객체의 시각과 같은지 비교
        assertThat(found.getCreatedAt()).isCloseTo(user.getCreatedAt(), within(1, ChronoUnit.MILLIS));
        assertThat(found.getUpdatedAt()).isCloseTo(user.getUpdatedAt(), within(1, ChronoUnit.MILLIS));
    }

    // LOCAL 회원 저장 시 모든 필드가 정확하게 DB에 저장되는지 판별
    @Test
    @DisplayName("LOCAL 회원을 저장하고 다시 조회하면 모든 필드가 그대로 복원된다")
    void savesAndReloadsLocalUser() {
        // given
        User user = User.createLocal("user@example.com", PASSWORD_HASH, "홍길동");
        UUID publicId = user.getUuid();

        // when
        userRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        // then
        User found = userRepository.findById(user.getId()).orElseThrow();
        assertThat(found).isNotSameAs(user);
        assertThat(found.getId()).isNotNull().isEqualTo(user.getId());
        // 애플리케이션이 만든 UUID가 저장됐는지 확인한다.
        assertThat(found.getUuid()).isEqualTo(publicId);
        assertThat(found.getEmail()).isEqualTo("user@example.com");
        assertThat(found.getPasswordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(found.getNickname()).isEqualTo("홍길동");
        assertThat(found.getProfileImageUrl()).isNull();
        assertThat(found.getRole()).isEqualTo(UserRole.USER);
        assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(found.getProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(found.getCreatedAt()).isCloseTo(user.getCreatedAt(), within(1, ChronoUnit.MILLIS));
        assertThat(found.getUpdatedAt()).isCloseTo(user.getUpdatedAt(), within(1, ChronoUnit.MILLIS));
    }

    // Access Token의 sub(public_id)를 내부 ID로 바꾸는 조회(GR-32 M03-05-1)
    @Test
    @DisplayName("public_id로 회원의 내부 id만 조회하고, 없는 public_id는 빈 결과다")
    void findsIdByPublicId() {
        // given
        User user = userRepository.save(User.createLocal("user@example.com", PASSWORD_HASH, "홍길동"));
        entityManager.flush();
        entityManager.clear();

        // when, then
        assertThat(userRepository.findIdByPublicId(user.getUuid())).contains(user.getId());
        assertThat(userRepository.findIdByPublicId(UUID.randomUUID())).isEmpty();
    }

    // 이메일 조회 테스트
    @Test
    @DisplayName("정규화된 이메일로 저장된 회원을 조회하고 존재 여부를 확인한다")
    void findsUserByNormalizedEmail() {
        // given
        User user = userRepository.save(User.createLocal("user@example.com", PASSWORD_HASH, "홍길동"));
        entityManager.flush();
        entityManager.clear();

        // when
        Optional<User> found = userRepository.findByEmail("user@example.com");
        boolean exists = userRepository.existsByEmail("user@example.com");

        // then
        assertThat(found).get().extracting(User::getId).isEqualTo(user.getId());
        assertThat(exists).isTrue();
    }

    // 저장되지 않은 이메일 조회 안되는지 테스트
    @Test
    @DisplayName("저장되지 않은 이메일은 조회되지 않고 존재하지 않는 것으로 판단한다")
    void returnsEmptyForUnknownEmail() {
        // given
        userRepository.save(User.createLocal("user@example.com", PASSWORD_HASH, "홍길동"));
        entityManager.flush();
        entityManager.clear();

        // when & then
        assertThat(userRepository.findByEmail("other@example.com")).isEmpty();
        assertThat(userRepository.existsByEmail("other@example.com")).isFalse();
    }

    // Repository에 정규화 책임이 없는지 검증하는 테스트
    @Test
    @DisplayName("Repository는 이메일을 정규화하지 않고 전달받은 값 그대로 조회한다")
    void doesNotNormalizeEmailOnLookup() {
        // given
        userRepository.save(User.createLocal("user@example.com", PASSWORD_HASH, "홍길동"));
        entityManager.flush();
        entityManager.clear();

        // when & then
        // 정규화는 호출자 책임이므로 대소문자·공백이 다른 값은 다른 이메일로 취급한다.
        assertThat(userRepository.findByEmail("User@Example.com")).isEmpty();
        assertThat(userRepository.existsByEmail(" user@example.com ")).isFalse();
    }

    @Test
    @DisplayName("같은 이메일로 두 번 저장하면 DB UNIQUE 제약 위반이 발생한다")
    void rejectsDuplicateEmail() {
        // given
        userRepository.saveAndFlush(User.createLocal("user@example.com", PASSWORD_HASH, "홍길동"));
        User duplicate = User.createLocal("user@example.com", PASSWORD_HASH, "김길동");

        // when & then
        // PostgreSQL은 제약 위반 뒤 트랜잭션을 중단 상태로 두므로 이후 조회를 이어가지 않는다.
        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("uq_users_email");
    }

    @Test
    @DisplayName("대소문자만 다른 닉네임으로 저장하면 DB 유니크 인덱스 위반이 발생한다")
    void rejectsNicknameDifferingOnlyInCase() {
        // given
        userRepository.saveAndFlush(User.createLocal("first@example.com", PASSWORD_HASH, "Grab"));
        User duplicate = User.createLocal("second@example.com", PASSWORD_HASH, "grab");

        // when & then
        // 중복 판단은 lower(nickname) 기준이므로 대소문자만 다른 값도 같은 닉네임으로 거부한다.
        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("uq_users_nickname_lower");
    }

    // 가입 서비스의 닉네임 사전 검사(GR-29 M02-02)
    @Test
    @DisplayName("닉네임 존재 여부는 대소문자를 무시하고 판단하며, 다른 닉네임은 찾지 않는다")
    void checksNicknameIgnoringCase() {
        // given
        userRepository.save(User.createLocal("first@example.com", PASSWORD_HASH, "Grab_01"));
        userRepository.save(User.createLocal("second@example.com", PASSWORD_HASH, "드롭헌터"));
        entityManager.flush();
        entityManager.clear();

        // when & then
        assertThat(userRepository.existsByNicknameIgnoreCase("Grab_01")).isTrue();
        assertThat(userRepository.existsByNicknameIgnoreCase("grab_01")).isTrue();
        assertThat(userRepository.existsByNicknameIgnoreCase("GRAB_01")).isTrue();
        assertThat(userRepository.existsByNicknameIgnoreCase("드롭헌터")).isTrue();
        assertThat(userRepository.existsByNicknameIgnoreCase("Grab_02")).isFalse();
        assertThat(userRepository.existsByNicknameIgnoreCase("Grab")).isFalse();
    }

    // createLocal은 null 해시를 먼저 거부하므로, DB 제약은 SQL로 직접 검증한다.
    @Test
    @DisplayName("LOCAL 회원은 비밀번호 해시 없이 DB에 저장할 수 없다")
    void rejectsLocalUserWithoutPasswordHash() {
        // when & then
        // PostgreSQL은 제약 위반 뒤 트랜잭션을 중단 상태로 두므로 이후 조회를 이어가지 않는다.
        assertThatThrownBy(() -> insertUserWithoutPasswordHash("local@example.com", "LOCAL", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_users_local_password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"KAKAO", "GOOGLE"})
    @DisplayName("소셜 회원은 비밀번호 해시 없이 제공자 식별자와 함께 DB에 저장할 수 있다")
    void savesSocialUserWithoutPasswordHash(String provider) {
        // given
        insertUserWithoutPasswordHash("social@example.com", provider, "sub-1");

        // when
        User found = userRepository.findByEmail("social@example.com").orElseThrow();

        // then
        assertThat(found.getProvider()).isEqualTo(AuthProvider.valueOf(provider));
        assertThat(found.getProviderUserId()).isEqualTo("sub-1");
        assertThat(found.getPasswordHash()).isNull();
    }

    // GR-46 M00-01: 소셜 회원은 제공자 식별자로 찾는다. 이메일은 식별에 쓰지 않는다
    @Test
    @DisplayName("createSocial로 저장한 회원을 제공자와 식별자로 조회하고, 제공자나 식별자가 다르면 찾지 않는다")
    void findsSocialUserByProviderAndProviderUserId() {
        // given
        User user = userRepository.save(User.createSocial("kakao@example.com", AuthProvider.KAKAO, "sub-1", "드롭헌터"));
        entityManager.flush();
        entityManager.clear();

        // when & then
        User found = userRepository.findByProviderAndProviderUserId(AuthProvider.KAKAO, "sub-1").orElseThrow();
        assertThat(found.getId()).isEqualTo(user.getId());
        assertThat(found.getPasswordHash()).isNull();
        assertThat(found.getRole()).isEqualTo(UserRole.USER);
        assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "sub-1")).isEmpty();
        assertThat(userRepository.findByProviderAndProviderUserId(AuthProvider.KAKAO, "sub-2")).isEmpty();
    }

    @Test
    @DisplayName("같은 제공자의 같은 식별자로 두 번 저장하면 uq_users_provider_user 위반이다")
    void rejectsDuplicateProviderUserId() {
        // given
        userRepository.saveAndFlush(User.createSocial("first@example.com", AuthProvider.GOOGLE, "sub-1", "첫번째"));
        User duplicate = User.createSocial("second@example.com", AuthProvider.GOOGLE, "sub-1", "두번째");

        // when & then
        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("uq_users_provider_user");
    }

    @Test
    @DisplayName("다른 제공자라면 같은 식별자 값을 함께 저장할 수 있다")
    void allowsSameProviderUserIdAcrossProviders() {
        // when
        userRepository.saveAndFlush(User.createSocial("kakao@example.com", AuthProvider.KAKAO, "sub-1", "카카오"));
        userRepository.saveAndFlush(User.createSocial("google@example.com", AuthProvider.GOOGLE, "sub-1", "구글"));

        // then
        assertThat(userRepository.findByProviderAndProviderUserId(AuthProvider.KAKAO, "sub-1")).isPresent();
        assertThat(userRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "sub-1")).isPresent();
    }

    @Test
    @DisplayName("소셜 회원은 제공자 식별자 없이 저장할 수 없다")
    void rejectsSocialUserWithoutProviderUserId() {
        // when & then
        assertThatThrownBy(() -> insertUserWithoutPasswordHash("social@example.com", "KAKAO", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_users_social_identifier");
    }

    @Test
    @DisplayName("LOCAL 회원은 제공자 식별자를 가질 수 없다")
    void rejectsLocalUserWithProviderUserId() {
        // when & then
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, nickname, provider, provider_user_id)
                VALUES ('local@example.com', ?, '홍길동', 'LOCAL', 'sub-1')
                """, PASSWORD_HASH))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_users_social_identifier");
    }

    // GR-129 M05-03: 내 닉네임 수정의 중복 사전 검사는 본인 행을 빼고 대소문자를 무시한다(M00-05)
    @Test
    @DisplayName("본인을 뺀 닉네임 중복 검사는 다른 회원의 닉네임만 대소문자를 무시해 찾는다")
    void checksNicknameExcludingUser() {
        // given
        User me = userRepository.save(User.createLocal("me@example.com", PASSWORD_HASH, "Grab_01"));
        userRepository.save(User.createLocal("other@example.com", PASSWORD_HASH, "드롭헌터"));
        entityManager.flush();
        entityManager.clear();

        // when & then
        assertThat(userRepository.existsByNicknameIgnoreCaseExcludingUser("grab_01", me.getId())).isFalse();
        assertThat(userRepository.existsByNicknameIgnoreCaseExcludingUser("Grab_01", me.getId())).isFalse();
        assertThat(userRepository.existsByNicknameIgnoreCaseExcludingUser("드롭헌터", me.getId())).isTrue();
        assertThat(userRepository.existsByNicknameIgnoreCaseExcludingUser("Grab_02", me.getId())).isFalse();
    }

    @Test
    @DisplayName("본인 닉네임의 대소문자만 바꾸면 유니크 인덱스에 걸리지 않는다")
    void allowsCaseOnlyNicknameChange() {
        // given
        User me = userRepository.saveAndFlush(User.createLocal("me@example.com", PASSWORD_HASH, "Grab"));

        // when
        me.changeNickname("grab");
        userRepository.flush();
        entityManager.clear();

        // then
        assertThat(userRepository.findById(me.getId()).orElseThrow().getNickname()).isEqualTo("grab");
    }

    // 서비스가 이 제약 이름으로만 DUPLICATE_NICKNAME으로 바꾸므로 이름을 고정한다(UserMeCommandService)
    @Test
    @DisplayName("다른 회원과 대소문자만 다른 닉네임으로 바꾸면 flush에서 uq_users_nickname_lower 위반이다")
    void rejectsNicknameChangeToOthersNickname() {
        // given
        userRepository.saveAndFlush(User.createLocal("other@example.com", PASSWORD_HASH, "Grab"));
        User me = userRepository.saveAndFlush(User.createLocal("me@example.com", PASSWORD_HASH, "드롭헌터"));

        // when
        me.changeNickname("GRAB");

        // then
        assertThatThrownBy(() -> userRepository.flush())
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("uq_users_nickname_lower");
    }

    // GR-129 M05-03: 비밀번호 변경의 조건부 갱신(M03-01·02). 네이티브 쿼리는 기동 시 검증되지 않아 이 테스트가 첫 실행 확인이다
    @Test
    @DisplayName("읽은 해시가 그대로면 비밀번호 해시와 updated_at을 바꾸고 1을 돌려준다")
    void updatesPasswordHashWhenUnchanged() {
        // given
        User me = userRepository.saveAndFlush(User.createLocal("me@example.com", PASSWORD_HASH, "드롭헌터"));
        setUpdatedAt(me.getId(), "2026-01-01T00:00:00Z");
        entityManager.clear();

        // when
        int updated = userRepository.updatePasswordHashIfUnchanged(me.getId(), PASSWORD_HASH, "$argon2id$new");

        // then: 엔티티를 거치지 않는 UPDATE라 영속성 컨텍스트를 비우고 DB 값을 읽는다
        entityManager.clear();
        User found = userRepository.findById(me.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(found.getPasswordHash()).isEqualTo("$argon2id$new");
        assertThat(found.getUpdatedAt()).isAfter(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("읽은 해시와 저장된 해시가 다르면 바꾸지 않고 0을 돌려준다")
    void skipsPasswordUpdateWhenHashChanged() {
        // given
        User me = userRepository.saveAndFlush(User.createLocal("me@example.com", PASSWORD_HASH, "드롭헌터"));
        entityManager.clear();

        // when: 검증 뒤 다른 요청이 비밀번호를 바꾼 경우처럼 다른 해시를 읽었다고 가정한다
        int updated = userRepository.updatePasswordHashIfUnchanged(me.getId(), "$argon2id$stale", "$argon2id$new");

        // then
        entityManager.clear();
        assertThat(updated).isZero();
        assertThat(userRepository.findById(me.getId()).orElseThrow().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    @DisplayName("소셜 회원은 비밀번호 해시를 바꾸지 않고 0을 돌려준다")
    void skipsPasswordUpdateForSocialUser() {
        // given: 해시가 없는 소셜 회원에 같은 값을 넘겨도 provider 조건으로 걸러진다
        User social = userRepository.saveAndFlush(
                User.createSocial("social@example.com", AuthProvider.KAKAO, "sub-1", "드롭헌터"));
        entityManager.clear();

        // when
        int updated = userRepository.updatePasswordHashIfUnchanged(social.getId(), PASSWORD_HASH, "$argon2id$new");

        // then
        entityManager.clear();
        assertThat(updated).isZero();
        assertThat(userRepository.findById(social.getId()).orElseThrow().getPasswordHash()).isNull();
    }

    private void setUpdatedAt(Long userId, String updatedAt) {
        jdbcTemplate.update("UPDATE users SET updated_at = ?::timestamptz WHERE id = ?", updatedAt, userId);
    }

    private void insertUserWithoutPasswordHash(String email, String provider, String providerUserId) {
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, nickname, provider, provider_user_id)
                VALUES (?, NULL, '홍길동', ?, ?)
                """, email, provider, providerUserId);
    }
}
