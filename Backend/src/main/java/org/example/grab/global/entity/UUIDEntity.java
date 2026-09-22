package org.example.grab.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class UUIDEntity extends BaseEntity {

    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private UUID uuid = UUID.randomUUID();
}
