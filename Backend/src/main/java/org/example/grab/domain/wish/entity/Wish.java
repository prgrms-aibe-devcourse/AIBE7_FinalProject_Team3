package org.example.grab.domain.wish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.global.entity.BaseEntity;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 사용자가 WISH 상태의 DROP에 등록한 관심 표시. 취소해도 행은 남고 {@code canceled_at}으로 활성 여부를 구분한다.
 * DROP·User는 ID로만 참조한다(도메인 간 연관관계 금지).
 */
@Getter
@Entity
@Table(name = "wishes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wish extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "drop_id", nullable = false)
    private Long dropId;

    @Column(name = "activated_at", nullable = false)
    private OffsetDateTime activatedAt;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    private Wish(Long userId, Long dropId, OffsetDateTime activatedAt) {
        this.userId = Objects.requireNonNull(userId);
        this.dropId = Objects.requireNonNull(dropId);
        this.activatedAt = Objects.requireNonNull(activatedAt);
    }

    public static Wish activate(Long userId, Long dropId, OffsetDateTime now) {
        return new Wish(userId, dropId, now);
    }

    /** 취소된 WISH를 다시 활성화한다. 최근 등록 시각만 갱신하고 취소 시각은 지운다. */
    public void reactivate(OffsetDateTime now) {
        this.activatedAt = Objects.requireNonNull(now);
        this.canceledAt = null;
    }

    public void cancel(OffsetDateTime now) {
        this.canceledAt = Objects.requireNonNull(now);
    }

    public boolean isActive() {
        return canceledAt == null;
    }
}
