package org.example.grab.domain.demand.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.grab.global.entity.UUIDEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Objects;

// 판매자가 저장한 판매 계획. 계산 결과는 저장 시점 스냅샷으로 남기고, 결정은 재고·DROP에 반영하지 않는다(ERD.md 1.7).
@Getter
@Entity
@Table(name = "demand_plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DemandPlan extends UUIDEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "drop_id", nullable = false, updatable = false)
    private Long dropId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String assumptions;

    @Column(name = "conversion_basis", updatable = false, length = 20)
    private String conversionBasis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String result;

    @Column(name = "calculation_version", nullable = false, updatable = false, length = 20)
    private String calculationVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DemandPlanStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decided_quantities", columnDefinition = "jsonb")
    private String decidedQuantities;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "explanation_status", nullable = false, length = 20)
    private ExplanationStatus explanationStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String explanation;

    private DemandPlan(Long dropId, Long createdBy, String assumptions, String conversionBasis, String result,
                       String calculationVersion, ExplanationStatus explanationStatus) {
        this.dropId = Objects.requireNonNull(dropId);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.assumptions = Objects.requireNonNull(assumptions);
        this.conversionBasis = conversionBasis;
        this.result = Objects.requireNonNull(result);
        this.calculationVersion = Objects.requireNonNull(calculationVersion);
        this.status = DemandPlanStatus.DRAFT;
        this.explanationStatus = Objects.requireNonNull(explanationStatus);
    }

    public static DemandPlan draft(Long dropId, Long createdBy, String assumptions, String conversionBasis,
                                   String result, String calculationVersion, ExplanationStatus explanationStatus) {
        return new DemandPlan(dropId, createdBy, assumptions, conversionBasis, result, calculationVersion,
                explanationStatus);
    }

    public boolean isDecided() {
        return status != DemandPlanStatus.DRAFT;
    }

    // 호출 전에 행을 잠그고 DRAFT인지 확인한다. 수량은 MODIFIED에만 남긴다.
    public void decide(DemandPlanStatus decision, String decidedQuantities, String reason, OffsetDateTime decidedAt) {
        if (isDecided() || decision == DemandPlanStatus.DRAFT) {
            throw new IllegalStateException("판매 계획 결정은 DRAFT에서 한 번만 기록할 수 있습니다.");
        }
        if ((decision == DemandPlanStatus.MODIFIED) != (decidedQuantities != null)) {
            throw new IllegalArgumentException("판매자가 정한 수량은 MODIFIED에만 기록합니다.");
        }
        this.status = decision;
        this.decidedQuantities = decidedQuantities;
        this.decisionReason = reason;
        this.decidedAt = Objects.requireNonNull(decidedAt);
    }
}
