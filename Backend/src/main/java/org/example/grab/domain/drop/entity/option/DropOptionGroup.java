package org.example.grab.domain.drop.entity.option;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.global.entity.BaseEntity;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "drop_option_groups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropOptionGroup extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drop_id", nullable = false)
    private Drop drop;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @BatchSize(size = 100)
    private List<DropOptionValue> values = new ArrayList<>();

    private DropOptionGroup(Drop drop, String name, int sortOrder) {
        this.drop = drop;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public static DropOptionGroup create(Drop drop, String name, int sortOrder) {
        return new DropOptionGroup(drop, name, sortOrder);
    }

    public void addValue(DropOptionValue value) {
        values.add(value);
    }
}
