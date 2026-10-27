package org.example.grab.domain.demand.option;

import org.example.grab.domain.demand.repository.OptionMatchRepository.OptionSales;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/*
    비슷한 상품 표본의 옵션 후보(DEMAND.md 7.3). 정규화한 그룹명·값이 같으면 하나로 합치고,
    판매량 1 이상인 후보만 판매량이 많은 순으로 최대 maxCandidates개 남긴다. 번호(id)는 목록 순서다.
 */
public final class OptionCandidates {

    private OptionCandidates() {
    }

    // 표시용 글자는 처음 본 원문을 쓴다. dropIds·sellerIds는 최소 집계 단위 판정에만 쓴다
    public record Candidate(int id, String groupName, String value, String normalizedGroup, String normalizedValue,
                            int sold, Set<Long> dropIds, Set<Long> sellerIds) {
    }

    // 앞뒤 공백 제거, 영문 소문자, 공백 제거
    public static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public static List<Candidate> collect(List<OptionSales> rows, int maxCandidates) {
        Map<String, Builder> merged = new LinkedHashMap<>();
        for (OptionSales row : rows) {
            String group = normalize(row.groupName());
            String value = normalize(row.value());
            if (group.isEmpty() || value.isEmpty()) {
                continue;
            }
            merged.computeIfAbsent(group + "\u0000" + value, key -> new Builder(row.groupName(), row.value(), group, value))
                    .add(row);
        }
        List<Builder> sorted = merged.values().stream()
                .filter(builder -> builder.sold > 0)
                .sorted(Comparator.comparingInt((Builder builder) -> builder.sold).reversed()
                        .thenComparing(builder -> builder.normalizedGroup)
                        .thenComparing(builder -> builder.normalizedValue))
                .limit(maxCandidates)
                .toList();
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Builder builder = sorted.get(i);
            candidates.add(new Candidate(i, builder.groupName.strip(), builder.value.strip(), builder.normalizedGroup,
                    builder.normalizedValue, builder.sold, Set.copyOf(builder.dropIds), Set.copyOf(builder.sellerIds)));
        }
        return List.copyOf(candidates);
    }

    private static final class Builder {
        private final String groupName;
        private final String value;
        private final String normalizedGroup;
        private final String normalizedValue;
        private int sold;
        private final Set<Long> dropIds = new HashSet<>();
        private final Set<Long> sellerIds = new HashSet<>();

        private Builder(String groupName, String value, String normalizedGroup, String normalizedValue) {
            this.groupName = groupName;
            this.value = value;
            this.normalizedGroup = normalizedGroup;
            this.normalizedValue = normalizedValue;
        }

        private void add(OptionSales row) {
            sold += row.sold();
            if (row.sold() > 0) {
                dropIds.add(row.dropId());
                sellerIds.add(row.sellerId());
            }
        }
    }
}
