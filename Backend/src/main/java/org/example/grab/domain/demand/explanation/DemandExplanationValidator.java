package org.example.grab.domain.demand.explanation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
    LLM 응답이 정해진 형식이고, 문장 속 모든 숫자가 입력에 있는 값인지 확인한다(DEMAND.md 3절).
    하나라도 어긋나면 설명을 버린다. LLM이 수량을 새로 만들거나 바꾼 설명을 판매자에게 보여 주지 않기 위해서다.
    입력값의 절댓값도 허용한다. differenceFromCurrent -35를 "35개 많음"처럼 부호 없이 쓰기 때문이다.
 */
public final class DemandExplanationValidator {

    static final int MAX_TEXT_LENGTH = 300;
    static final int MAX_ITEMS = 5;
    private static final List<String> LIST_FIELDS = List.of("reasons", "risks", "dataGaps", "notRecommended");
    // 1,740처럼 천 단위 쉼표가 들어간 수도 하나로 읽는다. 부호는 절댓값으로 비교하므로 읽지 않는다
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?");
    // 시장 표본 DROP 수 구간 코드(DEMAND.md 1.3)
    private static final Pattern BAND = Pattern.compile("\\d+_(?:TO_\\d+|PLUS)");

    private DemandExplanationValidator() {
    }

    public static Optional<DemandExplanation> validate(String response, String input, ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        Optional<DemandExplanation> explanation = parse(root);
        if (explanation.isEmpty()) {
            return Optional.empty();
        }
        Set<BigDecimal> allowed = allowedNumbers(objectMapper.readTree(input));
        boolean numbersMatch = explanation.get().texts().stream()
                .flatMap(text -> numbers(text).stream())
                .allMatch(allowed::contains);
        return numbersMatch ? explanation : Optional.empty();
    }

    private static Optional<DemandExplanation> parse(JsonNode root) {
        if (root == null || !root.isObject() || !isText(root.get("summary"))) {
            return Optional.empty();
        }
        List<List<String>> lists = new ArrayList<>();
        for (String field : LIST_FIELDS) {
            JsonNode node = root.get(field);
            if (node == null || node.isNull()) {
                lists.add(List.of());
                continue;
            }
            if (!node.isArray() || node.size() > MAX_ITEMS) {
                return Optional.empty();
            }
            List<String> items = new ArrayList<>();
            for (JsonNode item : node) {
                if (!isText(item)) {
                    return Optional.empty();
                }
                items.add(item.asString().strip());
            }
            lists.add(List.copyOf(items));
        }
        return Optional.of(new DemandExplanation(
                root.get("summary").asString().strip(), lists.get(0), lists.get(1), lists.get(2), lists.get(3)));
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isString() && !node.asString().isBlank()
                && node.asString().length() <= MAX_TEXT_LENGTH;
    }

    static Set<BigDecimal> allowedNumbers(JsonNode input) {
        Set<BigDecimal> allowed = new HashSet<>();
        collect(input, allowed);
        return allowed;
    }

    private static void collect(JsonNode node, Set<BigDecimal> allowed) {
        if (node.isNumber()) {
            allowed.add(normalize(node.decimalValue().abs()));
        } else if (node.isString() && BAND.matcher(node.asString()).matches()) {
            // 시장 표본 DROP 수 구간 코드(10_TO_19, 20_PLUS)의 끝값은 설명에 "DROP 10~19개"처럼 쓸 수 있다.
            // 다른 문자열의 숫자는 허용하지 않는다
            allowed.addAll(numbers(node.asString().replace('_', ' ')));
        }
        for (JsonNode child : node) {
            collect(child, allowed);
        }
    }

    static List<BigDecimal> numbers(String text) {
        List<BigDecimal> numbers = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(normalize(new BigDecimal(matcher.group().replace(",", ""))));
        }
        return numbers;
    }

    // 1.0과 1, 30과 30.00을 같은 값으로 비교한다
    private static BigDecimal normalize(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
