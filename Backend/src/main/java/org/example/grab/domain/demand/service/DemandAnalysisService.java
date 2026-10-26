package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.config.DemandSuggestionProperties;
import org.example.grab.domain.demand.dto.ConversionSample;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Conversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.DailyWish;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Demand;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.MarketConversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Suggestion;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.dto.DemandOption;
import org.example.grab.domain.demand.dto.SampleSelection;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.repository.DemandAnalysisRepository;
import org.example.grab.domain.demand.repository.DemandAnalysisRepository.TargetDrop;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class DemandAnalysisService {

    static final String CALCULATION_VERSION = "v1";
    static final int MAX_SAMPLE_DROPS = 20;
    static final int TREND_DAYS = 14;
    static final String WISH_ONLY_NOTE = "WISH 없이 구매한 사용자는 전환율에 포함하지 않았습니다.";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DemandAnalysisRepository demandAnalysisRepository;
    private final DemandSuggestionProperties suggestionProperties;
    private final Clock clock;

    @Autowired
    public DemandAnalysisService(
            DemandAnalysisRepository demandAnalysisRepository,
            DemandSuggestionProperties suggestionProperties
    ) {
        this(demandAnalysisRepository, suggestionProperties, Clock.systemUTC());
    }

    DemandAnalysisService(
            DemandAnalysisRepository demandAnalysisRepository,
            DemandSuggestionProperties suggestionProperties,
            Clock clock
    ) {
        this.demandAnalysisRepository = demandAnalysisRepository;
        this.suggestionProperties = suggestionProperties;
        this.clock = clock;
    }

    // 계산만 하고 저장하지 않는다. 같은 데이터와 가정이면 같은 결과다(DEMAND-002·003).
    @Transactional(readOnly = true)
    public DemandAnalysisResponse analyze(long sellerId, long dropId, DemandCalculateRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        TargetDrop drop = demandAnalysisRepository.findTargetDrop(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        if (drop.sellerId() != sellerId) {
            throw new BusinessException(DropErrorCode.DROP_ACCESS_DENIED);
        }
        if (!"WISH".equals(drop.status()) || !now.isBefore(drop.saleStartsAt())) {
            throw new BusinessException(DemandErrorCode.DEMAND_ANALYSIS_UNAVAILABLE);
        }

        long activeWishCount = demandAnalysisRepository.countActiveWishes(dropId);
        long hoursUntilSaleStart = Duration.between(now, drop.saleStartsAt()).toHours();
        ConversionSample ownSample = drop.categoryId() == null
                ? ConversionSample.empty()
                : demandAnalysisRepository.findOwnConversionSample(sellerId, drop.categoryId(), dropId, MAX_SAMPLE_DROPS);
        // 시장 평균은 다른 판매자 데이터 보호 규칙과 함께 GR-142 ②에서 계산한다
        MarketConversion market = MarketConversion.unavailable();
        List<DemandOption> options = demandAnalysisRepository.findActiveOptions(dropId);

        DemandPlanCalculator.Result result = DemandPlanCalculator.calculate(
                activeWishCount, ownSample, market, options, request);
        return new DemandAnalysisResponse(
                dropId,
                now,
                CALCULATION_VERSION,
                new Suggestion(activeWishCount >= suggestionProperties.minActiveWishes()
                        || hoursUntilSaleStart <= suggestionProperties.hoursBeforeStart()),
                new Demand(activeWishCount, hoursUntilSaleStart, dailyWishes(dropId, now), true),
                new Conversion(SampleSelection.CATEGORY, result.own(), market),
                result.assumptions(),
                result.plan(),
                result.dataGaps(),
                List.of(WISH_ONLY_NOTE));
    }

    // 오늘을 포함한 최근 14일(KST)을 등록이 없는 날도 0으로 채워 날짜순으로 반환한다
    private List<DailyWish> dailyWishes(long dropId, OffsetDateTime now) {
        LocalDate today = now.atZoneSameInstant(KST).toLocalDate();
        LocalDate firstDay = today.minusDays(TREND_DAYS - 1);
        Map<LocalDate, Long> counts = demandAnalysisRepository.countDailyActiveWishes(
                dropId, firstDay.atStartOfDay(KST).toOffsetDateTime());
        return Stream.iterate(firstDay, date -> date.plusDays(1))
                .limit(TREND_DAYS)
                .map(date -> new DailyWish(date, counts.getOrDefault(date, 0L)))
                .toList();
    }
}
