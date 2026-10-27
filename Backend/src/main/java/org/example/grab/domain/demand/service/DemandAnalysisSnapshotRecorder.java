package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.AssumptionSource;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Assumption;
import org.example.grab.domain.demand.repository.DemandAnalysisRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;

/*
    판매 시작 시점 수요 분석을 계산해 기록한다(DEMAND.md 2.3). 상태 전환 트랜잭션과 분리된 새 트랜잭션에서 실행한다.
    판매자가 화면에서 고른 기준·가정은 받지 않고 기본 가정으로 계산한다.
 */
@Component
public class DemandAnalysisSnapshotRecorder {

    private final DemandAnalysisService demandAnalysisService;
    private final DemandAnalysisRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DemandAnalysisSnapshotRecorder(
            DemandAnalysisService demandAnalysisService,
            DemandAnalysisRepository repository,
            ObjectMapper objectMapper
    ) {
        this(demandAnalysisService, repository, objectMapper, Clock.systemUTC());
    }

    DemandAnalysisSnapshotRecorder(
            DemandAnalysisService demandAnalysisService,
            DemandAnalysisRepository repository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.demandAnalysisService = demandAnalysisService;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long dropId) {
        repository.findTargetDrop(dropId).ifPresent(drop -> {
            DemandAnalysisResponse result = demandAnalysisService.analyzeAtSaleStart(drop);
            repository.saveSnapshot(
                    dropId,
                    result.calculationVersion(),
                    conversionBasis(result.assumptions().conversionRate()),
                    objectMapper.writeValueAsString(result),
                    result.demand().activeWishCount(),
                    result.plan().expected(),
                    result.plan().recommended(),
                    result.plan().currentTotalQuantity(),
                    OffsetDateTime.now(clock));
        });
    }

    // 기본 가정에는 판매자 입력 전환율이 없으므로 내 실적·시장 평균만 남는다. 기준이 없으면 null이다
    private static String conversionBasis(Assumption conversionRate) {
        if (conversionRate == null || conversionRate.source() == null) {
            return null;
        }
        AssumptionSource source = conversionRate.source();
        return source == AssumptionSource.OWN || source == AssumptionSource.MARKET ? source.name() : null;
    }
}
