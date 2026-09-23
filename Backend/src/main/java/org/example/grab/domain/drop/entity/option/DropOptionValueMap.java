package org.example.grab.domain.drop.entity.option;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.global.entity.BaseEntity;

@Entity
@Table(name = "drop_option_value_maps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropOptionValueMap extends BaseEntity {

    @EmbeddedId
    private DropOptionValueMapId id = new DropOptionValueMapId();

    @MapsId("optionId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    private DropOption option;

    @MapsId("groupId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private DropOptionGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "value_id", nullable = false)
    private DropOptionValue value;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drop_id", nullable = false)
    private Drop drop;

    private DropOptionValueMap(DropOption option, DropOptionValue value) {
        this.option = option;
        this.value = value;
        this.group = value.getGroup();
        this.drop = option.getDrop();
    }

    public static DropOptionValueMap create(DropOption option, DropOptionValue value) {
        return new DropOptionValueMap(option, value);
    }
}
