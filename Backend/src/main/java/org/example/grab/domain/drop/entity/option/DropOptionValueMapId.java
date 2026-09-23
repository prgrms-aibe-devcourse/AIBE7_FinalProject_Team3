package org.example.grab.domain.drop.entity.option;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropOptionValueMapId implements Serializable {

    @Column(name = "option_id", nullable = false)
    private Long optionId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    public DropOptionValueMapId(Long optionId, Long groupId) {
        this.optionId = optionId;
        this.groupId = groupId;
    }
}
