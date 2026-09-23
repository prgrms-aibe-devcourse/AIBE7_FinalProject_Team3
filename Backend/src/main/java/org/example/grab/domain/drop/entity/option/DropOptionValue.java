package org.example.grab.domain.drop.entity.option;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.global.entity.BaseEntity;

@Entity
@Table(name = "drop_option_values")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropOptionValue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private DropOptionGroup group;

    @Column(name = "value", nullable = false, length = 100)
    private String value;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    private DropOptionValue(DropOptionGroup group, String value, int sortOrder) {
        this.group = group;
        this.value = value;
        this.sortOrder = sortOrder;
    }

    public static DropOptionValue create(DropOptionGroup group, String value, int sortOrder) {
        return new DropOptionValue(group, value, sortOrder);
    }
}
