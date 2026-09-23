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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.global.entity.BaseEntity;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "drop_options")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drop_id", nullable = false)
    private Drop drop;

    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "sold_quantity", nullable = false)
    private int soldQuantity;

    @Column(name = "withheld_quantity", nullable = false)
    private int withheldQuantity;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @OneToMany(mappedBy = "option", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DropOptionValueMap> valueMaps = new ArrayList<>();

    private DropOption(Drop drop, Long unitPrice, int totalQuantity, int sortOrder) {
        this.drop = drop;
        this.unitPrice = unitPrice;
        this.totalQuantity = totalQuantity;
        this.reservedQuantity = 0;
        this.soldQuantity = 0;
        this.withheldQuantity = 0;
        this.active = true;
        this.sortOrder = sortOrder;
    }

    public static DropOption create(Drop drop, Long unitPrice, int totalQuantity, int sortOrder) {
        return new DropOption(drop, unitPrice, totalQuantity, sortOrder);
    }

    public int getAvailableQuantity() {
        return totalQuantity - reservedQuantity - soldQuantity - withheldQuantity;
    }

    public void addValueMap(DropOptionValueMap valueMap) {
        valueMaps.add(valueMap);
    }
}
