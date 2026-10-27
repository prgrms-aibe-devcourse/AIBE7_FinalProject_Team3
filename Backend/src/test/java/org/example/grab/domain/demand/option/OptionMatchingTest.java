package org.example.grab.domain.demand.option;

import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.OptionAllocationResponse.Allocation;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionGroupInput;
import org.example.grab.domain.demand.option.OptionAllocationCalculator.GroupResult;
import org.example.grab.domain.demand.option.OptionCandidates.Candidate;
import org.example.grab.domain.demand.repository.OptionMatchRepository.OptionSales;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class OptionMatchingTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private static final List<OptionGroupInput> GROUPS = List.of(
            new OptionGroupInput("색상", List.of("블랙", "화이트")),
            new OptionGroupInput("사이즈", List.of("M", "L")));

    @Test
    @DisplayName("후보는 정규화한 그룹명·값이 같으면 합치고, 판매량 0은 빼고 판매량 순으로 상한까지 남긴다")
    void collect_mergesNormalizedAndOrdersBySold() {
        List<Candidate> candidates = OptionCandidates.collect(List.of(
                new OptionSales(1, 10, "색상", "Black ", 5),
                new OptionSales(2, 11, " 색상", "black", 7),
                new OptionSales(2, 11, "색상", "화이트", 20),
                new OptionSales(3, 12, "색상", "레드", 0)), 10);

        assertThat(candidates).extracting(Candidate::value).containsExactly("화이트", "Black");
        assertThat(candidates.get(1).sold()).isEqualTo(12);
        assertThat(candidates.get(1).dropIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(candidates).extracting(Candidate::id).containsExactly(0, 1);
        assertThat(OptionCandidates.collect(List.of(new OptionSales(1, 10, "색상", "a", 1),
                new OptionSales(1, 10, "색상", "b", 2)), 1)).hasSize(1);
    }

    @Test
    @DisplayName("글자 매칭은 그룹명·값의 정규화한 글자가 모두 같은 후보만 고른다")
    void byText_matchesSameGroupAndValue() {
        List<Candidate> candidates = OptionCandidates.collect(List.of(
                new OptionSales(1, 10, "색상", "블랙", 5),
                new OptionSales(1, 10, "컬러", "화이트", 5),
                new OptionSales(1, 10, "사이즈", "m", 5)), 10);

        OptionMatches matches = OptionMatches.byText(GROUPS, candidates);

        assertThat(matches.of(0, 0)).hasSize(1);
        assertThat(matches.of(0, 1)).isEmpty();
        assertThat(matches.of(1, 0)).hasSize(1);
        assertThat(matches.complete()).isFalse();
    }

    @Test
    @DisplayName("LLM 응답에서 범위 밖 번호는 버리고, JSON이 아니면 버리며, 저장 형식으로 그대로 다시 읽힌다")
    void parse_validatesAndRoundTrips() {
        OptionMatches parsed = OptionMatches.parse("""
                {"matches":[{"group":0,"value":1,"candidates":[2,9]},{"group":5,"value":0,"candidates":[1]}]}
                """, GROUPS, 3, objectMapper).orElseThrow();

        assertThat(parsed.of(0, 1)).containsExactly(2);
        assertThat(OptionMatches.parse("not json", GROUPS, 3, objectMapper)).isEmpty();
        assertThat(OptionMatches.parse(parsed.toJson(objectMapper), GROUPS, 3, objectMapper).orElseThrow())
                .isEqualTo(parsed);
    }

    @Test
    @DisplayName("한 그룹 안에서 두 값이 같은 후보를 고르면 그 후보는 둘 다에서 뺀다")
    void withoutSharedCandidates_removesDuplicates() {
        OptionMatches matches = OptionMatches.empty(GROUPS);
        matches.of(0, 0).addAll(Set.of(1, 2));
        matches.of(0, 1).add(2);
        matches.of(1, 0).add(2);

        OptionMatches cleaned = matches.withoutSharedCandidates();

        assertThat(cleaned.of(0, 0)).containsExactly(1);
        assertThat(cleaned.of(0, 1)).isEmpty();
        assertThat(cleaned.of(1, 0)).containsExactly(2);
    }

    @Test
    @DisplayName("모든 값이 매칭되고 최소 단위를 넘은 그룹만 판매 비율로, 나머지는 균등으로 나눈다")
    void groups_salesOrEven() {
        // given: 색상은 블랙 30·화이트 10(내 DROP 3개), 사이즈는 L만 매칭
        List<Candidate> candidates = List.of(
                candidate(0, 30, 1, 2, 3), candidate(1, 10, 1, 2, 3), candidate(2, 5, 1, 2, 3));
        OptionMatches matches = OptionMatches.empty(GROUPS);
        matches.of(0, 0).add(0);
        matches.of(0, 1).add(1);
        matches.of(1, 1).add(2);

        // when
        List<GroupResult> own = OptionAllocationCalculator.groups(GROUPS, matches, candidates, ConversionBasis.OWN);
        List<GroupResult> market = OptionAllocationCalculator.groups(GROUPS, matches, candidates,
                ConversionBasis.MARKET);

        // then
        assertThat(own.get(0).allocation().allocation()).isEqualTo(Allocation.SALES);
        assertThat(own.get(0).allocation().values()).extracting(value -> value.sharePercent()).containsExactly(75, 25);
        assertThat(own.get(1).allocation().allocation()).isEqualTo(Allocation.EVEN);
        // 시장 평균은 판매자 5명·DROP 10개를 못 넘어 균등이다
        assertThat(OptionAllocationCalculator.allEven(market)).isTrue();
    }

    @Test
    @DisplayName("SKU 비율은 그룹 비율의 곱이며 최대 나머지 방식으로 정수 배분한다")
    void allocate_multipliesGroupShares() {
        List<Candidate> candidates = List.of(candidate(0, 30, 1, 2, 3), candidate(1, 10, 1, 2, 3));
        OptionMatches matches = OptionMatches.empty(GROUPS);
        matches.of(0, 0).add(0);
        matches.of(0, 1).add(1);
        List<GroupResult> groups = OptionAllocationCalculator.groups(GROUPS, matches, candidates, ConversionBasis.OWN);

        // 블랙/M, 블랙/L, 화이트/M, 화이트/L: 0.375·0.375·0.125·0.125 × 44 = 16.5·16.5·5.5·5.5. 나머지가 같으면 앞 SKU부터
        int[] quantities = OptionAllocationCalculator.allocate(44, groups,
                new int[][]{{0, 0}, {0, 1}, {1, 0}, {1, 1}});

        assertThat(quantities).containsExactly(17, 17, 5, 5);
    }

    @Test
    @DisplayName("시장 평균은 매칭된 후보가 판매자 5명·DROP 10개 이상에서 나와야 판매 비율로 나눈다")
    void groups_marketNeedsMinimumUnit() {
        long[] drops = LongStream.rangeClosed(1, 10).toArray();
        List<Candidate> candidates = List.of(
                new Candidate(0, "색상", "블랙", "색상", "블랙", 30, set(drops), Set.of(1L, 2L, 3L, 4L, 5L)),
                new Candidate(1, "색상", "화이트", "색상", "화이트", 10, set(drops), Set.of(1L, 2L, 3L, 4L, 5L)));
        OptionMatches matches = OptionMatches.empty(GROUPS);
        matches.of(0, 0).add(0);
        matches.of(0, 1).add(1);

        List<GroupResult> groups = OptionAllocationCalculator.groups(GROUPS, matches, candidates,
                ConversionBasis.MARKET);

        assertThat(groups.get(0).allocation().allocation()).isEqualTo(Allocation.SALES);
    }

    private static Candidate candidate(int id, int sold, long... dropIds) {
        return new Candidate(id, "g", "v" + id, "g", "v" + id, sold, set(dropIds), Set.of(1L));
    }

    private static Set<Long> set(long... values) {
        return LongStream.of(values).boxed().collect(java.util.stream.Collectors.toSet());
    }
}
