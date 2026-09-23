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
class UserRepositoryTest {

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
        assertThat(found.getDisplayName()).isEqualTo("홍길동");
        assertThat(found.getProfileImageUrl()).isNull();
        assertThat(found.getRole()).isEqualTo(UserRole.USER);
        assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(found.getProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(found.getCreatedAt()).isCloseTo(user.getCreatedAt(), within(1, ChronoUnit.MILLIS));
        assertThat(found.getUpdatedAt()).isCloseTo(user.getUpdatedAt(), within(1, ChronoUnit.MILLIS));
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

    // createLocal은 null 해시를 먼저 거부하고 소셜 팩토리는 GR-46 범위라, DB 제약은 SQL로 직접 검증한다.
    @Test
    @DisplayName("LOCAL 회원은 비밀번호 해시 없이 DB에 저장할 수 없다")
    void rejectsLocalUserWithoutPasswordHash() {
        // when & then
        // PostgreSQL은 제약 위반 뒤 트랜잭션을 중단 상태로 두므로 이후 조회를 이어가지 않는다.
        assertThatThrownBy(() -> insertUserWithoutPasswordHash("local@example.com", "LOCAL"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_users_local_password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"KAKAO", "GOOGLE"})
    @DisplayName("소셜 회원은 비밀번호 해시 없이 DB에 저장할 수 있다")
    void savesSocialUserWithoutPasswordHash(String provider) {
        // given
        insertUserWithoutPasswordHash("social@example.com", provider);

        // when
        User found = userRepository.findByEmail("social@example.com").orElseThrow();

        // then
        assertThat(found.getProvider()).isEqualTo(AuthProvider.valueOf(provider));
        assertThat(found.getPasswordHash()).isNull();
    }

    private void insertUserWithoutPasswordHash(String email, String provider) {
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, display_name, provider)
                VALUES (?, NULL, '홍길동', ?)
                """, email, provider);
    }
}
