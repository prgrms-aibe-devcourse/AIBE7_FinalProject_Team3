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

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

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

    // 생성자는 정적 팩토리 createLocal로 접근 가능
    private User(
            String email,
            String passwordHash,
            String displayName,
            UserRole role,
            UserStatus status,
            AuthProvider provider
    ) {
        this.email = Objects.requireNonNull(email);
        this.passwordHash = passwordHash;
        this.displayName = Objects.requireNonNull(displayName);
        this.role = Objects.requireNonNull(role);
        this.status = Objects.requireNonNull(status);
        this.provider = Objects.requireNonNull(provider);
    }

    // 이메일 정규화와 비밀번호 해싱은 호출자(GR-28·GR-29)를 통해 데이터 처리를 끝낸 값을 받는다.
    public static User createLocal(String normalizedEmail, String passwordHash, String displayName) {
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
                displayName,
                UserRole.USER,
                UserStatus.ACTIVE,
                AuthProvider.LOCAL
        );
    }
}
