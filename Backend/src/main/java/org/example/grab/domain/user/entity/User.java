package org.example.grab.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.global.entity.UUIDEntity;

import java.util.Objects;

@Getter
@Entity
@Table(
        name = "users",
        // V1에서 정의된 uq_users_email 제약
        uniqueConstraints = @UniqueConstraint(name = "uq_users_email", columnNames = "email")
)
// JPA 객체 생성을 위한 기본 생성자, 외부 직접 생성은 제한
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends UUIDEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String nickname;

    // 초기 프로필 생성 시 프로필 이미지는 항상 null
    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    // 소셜 회원의 제공자 고유 사용자 식별자(OIDC sub). LOCAL은 null이다(V22 ck_users_social_identifier, GR-46 M00-01)
    @Column(name = "provider_user_id", length = 255)
    private String providerUserId;

    // 생성자는 정적 팩토리 createLocal·createSocial로 접근 가능
    private User(
            String email,
            String passwordHash,
            String nickname,
            UserRole role,
            UserStatus status,
            AuthProvider provider,
            String providerUserId
    ) {
        this.email = Objects.requireNonNull(email);
        this.passwordHash = passwordHash;
        this.nickname = Objects.requireNonNull(nickname);
        this.role = Objects.requireNonNull(role);
        this.status = Objects.requireNonNull(status);
        this.provider = Objects.requireNonNull(provider);
        this.providerUserId = providerUserId;
    }

    // 이메일 정규화와 비밀번호 해싱은 호출자(GR-28·GR-29)를 통해 데이터 처리를 끝낸 값을 받는다.
    public static User createLocal(String normalizedEmail, String passwordHash, String nickname) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("LOCAL 회원은 비밀번호 해시가 필요합니다.");
        }
        /*
            가입 시 Default
            - Role -> USER
            - Status -> ACTIVE
            - Provider -> LOCAL
         */
        return new User(
                normalizedEmail,
                passwordHash,
                nickname,
                UserRole.USER,
                UserStatus.ACTIVE,
                AuthProvider.LOCAL,
                null
        );
    }

    /*
        소셜 회원(MEMBER_AUTH.md 1.6). 비밀번호 해시는 저장하지 않고 제공자와 고유 사용자 식별자를 저장한다.
        이메일 정규화와 닉네임 검증은 호출자가 끝낸 값을 받는다. 가입 시 USER·ACTIVE다.
     */
    public static User createSocial(String normalizedEmail, AuthProvider provider, String providerUserId, String nickname) {
        if (provider == null || provider == AuthProvider.LOCAL) {
            throw new IllegalArgumentException("소셜 회원의 제공자는 KAKAO 또는 GOOGLE이어야 합니다.");
        }
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("소셜 회원은 제공자 고유 사용자 식별자가 필요합니다.");
        }
        return new User(
                normalizedEmail,
                null,
                nickname,
                UserRole.USER,
                UserStatus.ACTIVE,
                provider,
                providerUserId
        );
    }
}
