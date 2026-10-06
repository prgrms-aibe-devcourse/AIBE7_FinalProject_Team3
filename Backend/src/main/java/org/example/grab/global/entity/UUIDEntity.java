package org.example.grab.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.UUID;

@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// UUIDEntity 클래스명 보다 의미론적으로 바꿀 수도,,?
public abstract class UUIDEntity extends BaseEntity {

    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private UUID uuid = UUID.randomUUID();

    // 외부에서 발급한 UUID를 그대로 public_id로 쓰는 경우(예: DROP 이미지 발급 imageId)를 위한 생성자.
    protected UUIDEntity(UUID uuid) {
        this.uuid = Objects.requireNonNull(uuid);
    }
}
