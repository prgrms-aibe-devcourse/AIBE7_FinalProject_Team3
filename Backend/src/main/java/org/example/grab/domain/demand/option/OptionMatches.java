package org.example.grab.domain.demand.option;

import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionGroupInput;
import org.example.grab.domain.demand.option.OptionCandidates.Candidate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/*
    판매자 값마다 같은 값으로 고른 후보 번호(DEMAND.md 7.1·7.4).
    matches.get(그룹 번호).get(값 번호)가 후보 번호 집합이다. 번호는 요청 옵션 구성과 후보 목록 순서다.
 */
public record OptionMatches(List<List<Set<Integer>>> matches) {

    public Set<Integer> of(int group, int value) {
        return matches.get(group).get(value);
    }

    public static OptionMatches empty(List<OptionGroupInput> groups) {
        List<List<Set<Integer>>> matches = new ArrayList<>();
        for (OptionGroupInput group : groups) {
            List<Set<Integer>> values = new ArrayList<>();
            group.values().forEach(value -> values.add(new LinkedHashSet<>()));
            matches.add(values);
        }
        return new OptionMatches(matches);
    }

    /*
        글자 매칭(7.1 3단계): 판매자 그룹명·값과 정규화한 글자가 모두 같은 후보를 고른다.
     */
    public static OptionMatches byText(List<OptionGroupInput> groups, List<Candidate> candidates) {
        OptionMatches result = empty(groups);
        for (int g = 0; g < groups.size(); g++) {
            String group = OptionCandidates.normalize(groups.get(g).name());
            List<String> values = groups.get(g).values();
            for (int v = 0; v < values.size(); v++) {
                String value = OptionCandidates.normalize(values.get(v));
                for (Candidate candidate : candidates) {
                    if (candidate.normalizedGroup().equals(group) && candidate.normalizedValue().equals(value)) {
                        result.of(g, v).add(candidate.id());
                    }
                }
            }
        }
        return result;
    }

    // 모든 값에 후보가 하나 이상 있으면 LLM을 부르지 않는다
    public boolean complete() {
        return matches.stream().allMatch(values -> values.stream().noneMatch(Set::isEmpty));
    }

    /*
        LLM 응답 검증(7.4). JSON이 아니거나 형식이 다르면 빈 값이다. 범위 밖 번호는 버린다.
     */
    public static Optional<OptionMatches> parse(String response, List<OptionGroupInput> groups, int candidateCount,
                                                ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || !root.path("matches").isArray()) {
            return Optional.empty();
        }
        OptionMatches result = empty(groups);
        for (JsonNode item : root.path("matches")) {
            int g = item.path("group").asInt(-1);
            int v = item.path("value").asInt(-1);
            if (g < 0 || g >= groups.size() || v < 0 || v >= groups.get(g).values().size()
                    || !item.path("candidates").isArray()) {
                continue;
            }
            for (JsonNode candidate : item.path("candidates")) {
                if (candidate.isInt() && candidate.asInt() >= 0 && candidate.asInt() < candidateCount) {
                    result.of(g, v).add(candidate.asInt());
                }
            }
        }
        return Optional.of(result);
    }

    // 두 결과를 합친다(글자 매칭 + LLM 매칭)
    public OptionMatches union(OptionMatches other) {
        List<List<Set<Integer>>> merged = new ArrayList<>();
        for (int g = 0; g < matches.size(); g++) {
            List<Set<Integer>> values = new ArrayList<>();
            for (int v = 0; v < matches.get(g).size(); v++) {
                Set<Integer> ids = new LinkedHashSet<>(of(g, v));
                ids.addAll(other.of(g, v));
                values.add(ids);
            }
            merged.add(values);
        }
        return new OptionMatches(merged);
    }

    // 한 그룹 안에서 두 값 이상이 고른 후보는 모두에서 뺀다(7.4). 한 후보의 판매량을 두 값에 나눠 세지 않기 위해서다
    public OptionMatches withoutSharedCandidates() {
        List<List<Set<Integer>>> cleaned = new ArrayList<>();
        for (List<Set<Integer>> values : matches) {
            Map<Integer, Integer> counts = new HashMap<>();
            values.forEach(ids -> ids.forEach(id -> counts.merge(id, 1, Integer::sum)));
            List<Set<Integer>> group = new ArrayList<>();
            for (Set<Integer> ids : values) {
                Set<Integer> kept = new LinkedHashSet<>();
                ids.stream().filter(id -> counts.get(id) == 1).forEach(kept::add);
                group.add(kept);
            }
            cleaned.add(group);
        }
        return new OptionMatches(cleaned);
    }

    // 저장·LLM 출력과 같은 형식 {"matches":[{"group":0,"value":0,"candidates":[3]}]}
    public String toJson(ObjectMapper objectMapper) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int g = 0; g < matches.size(); g++) {
            for (int v = 0; v < matches.get(g).size(); v++) {
                items.add(Map.of("group", g, "value", v, "candidates", List.copyOf(of(g, v))));
            }
        }
        return objectMapper.writeValueAsString(Map.of("matches", items));
    }
}
