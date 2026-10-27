package org.example.grab.domain.demand.option;

import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.OptionAllocationResponse.Allocation;
import org.example.grab.domain.demand.dto.OptionAllocationResponse.GroupAllocation;
import org.example.grab.domain.demand.dto.OptionAllocationResponse.ValueShare;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionGroupInput;
import org.example.grab.domain.demand.option.OptionCandidates.Candidate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/*
    옵션별 비율과 배분(DEMAND.md 7.5). 매칭 결과와 후보 판매량으로 결정적으로 계산하며 LLM을 쓰지 않는다.
 */
public final class OptionAllocationCalculator {

    // 최소 집계 단위(DEMAND.md 1.3·7.5)
    static final int MIN_OWN_DROPS = 3;
    static final int MIN_MARKET_DROPS = 10;
    static final int MIN_MARKET_SELLERS = 5;

    private OptionAllocationCalculator() {
    }

    // shares는 SALES 그룹의 값별 비율(합 1), EVEN 그룹은 null이다
    public record GroupResult(GroupAllocation allocation, double[] shares) {
    }

    public static List<GroupResult> groups(List<OptionGroupInput> groups, OptionMatches matches,
                                           List<Candidate> candidates, ConversionBasis basis) {
        List<GroupResult> results = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            List<String> values = groups.get(g).values();
            long[] sold = new long[values.size()];
            Set<Long> dropIds = new HashSet<>();
            Set<Long> sellerIds = new HashSet<>();
            for (int v = 0; v < values.size(); v++) {
                for (int id : matches.of(g, v)) {
                    Candidate candidate = candidates.get(id);
                    sold[v] += candidate.sold();
                    dropIds.addAll(candidate.dropIds());
                    sellerIds.addAll(candidate.sellerIds());
                }
            }
            boolean allMatched = basis != null && IntStream.range(0, sold.length).allMatch(v -> sold[v] > 0);
            if (!allMatched || !meetsMinimumUnit(basis, dropIds.size(), sellerIds.size())) {
                results.add(new GroupResult(new GroupAllocation(groups.get(g).name(), Allocation.EVEN,
                        values.stream().map(value -> new ValueShare(value, null)).toList()), null));
                continue;
            }
            long total = 0;
            for (long value : sold) {
                total += value;
            }
            double[] shares = new double[sold.length];
            for (int v = 0; v < sold.length; v++) {
                shares[v] = (double) sold[v] / total;
            }
            int[] percents = largestRemainder(shares, 100);
            List<ValueShare> valueShares = new ArrayList<>();
            for (int v = 0; v < values.size(); v++) {
                valueShares.add(new ValueShare(values.get(v), percents[v]));
            }
            results.add(new GroupResult(new GroupAllocation(groups.get(g).name(), Allocation.SALES, valueShares),
                    shares));
        }
        return results;
    }

    /*
        SKU 비율 = 그룹별 값 비율의 곱(EVEN 그룹은 1/값 수). skuValues[i][g]는 SKU i의 그룹 g 값 번호다.
     */
    public static int[] allocate(int recommended, List<GroupResult> groups, int[][] skuValues) {
        double[] weights = new double[skuValues.length];
        for (int i = 0; i < skuValues.length; i++) {
            double weight = 1;
            for (int g = 0; g < groups.size(); g++) {
                double[] shares = groups.get(g).shares();
                int valueCount = groups.get(g).allocation().values().size();
                weight *= shares == null ? 1.0 / valueCount : shares[skuValues[i][g]];
            }
            weights[i] = weight;
        }
        double sum = 0;
        for (double weight : weights) {
            sum += weight;
        }
        double[] normalized = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            normalized[i] = sum == 0 ? 1.0 / weights.length : weights[i] / sum;
        }
        return largestRemainder(normalized, recommended);
    }

    public static boolean allEven(List<GroupResult> groups) {
        return groups.stream().allMatch(group -> group.shares() == null);
    }

    private static boolean meetsMinimumUnit(ConversionBasis basis, int drops, int sellers) {
        return basis == ConversionBasis.OWN
                ? drops >= MIN_OWN_DROPS
                : drops >= MIN_MARKET_DROPS && sellers >= MIN_MARKET_SELLERS;
    }

    // 최대 나머지 방식. 나머지가 같으면 앞 항목부터 준다(DEMAND.md 1.6과 같은 방식)
    static int[] largestRemainder(double[] shares, int total) {
        int[] allocated = new int[shares.length];
        double[] remainders = new double[shares.length];
        int used = 0;
        for (int i = 0; i < shares.length; i++) {
            double exact = shares[i] * total;
            allocated[i] = (int) Math.floor(exact + 1e-9);
            remainders[i] = exact - allocated[i];
            used += allocated[i];
        }
        Integer[] order = IntStream.range(0, shares.length).boxed()
                .sorted((a, b) -> Double.compare(remainders[b], remainders[a]) != 0
                        ? Double.compare(remainders[b], remainders[a]) : Integer.compare(a, b))
                .toArray(Integer[]::new);
        for (int i = 0; i < total - used && i < order.length; i++) {
            allocated[order[i]]++;
        }
        return allocated;
    }
}
