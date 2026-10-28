package org.example.grab.domain.demand.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/*
    임베딩 입력 텍스트(수요 분석 API 6.1). 공개 카탈로그 텍스트만 쓰고 판매자 정보·가격·수량·판매 실적은 넣지 않는다.
    형식을 바꾸면 VERSION을 올린다. source_hash에 버전이 들어가 다음 배치가 모든 DROP을 다시 만든다.
 */
public final class DropEmbeddingText {

    public static final String VERSION = "e1";
    public static final int DIMENSIONS = 768;
    static final int DESCRIPTION_LIMIT = 500;

    private DropEmbeddingText() {
    }

    public record Source(String categoryName, String name, String description, List<OptionGroup> optionGroups) {
    }

    // 값은 sort_order 순서다
    public record OptionGroup(String name, List<String> values) {
    }

    public static String build(Source source) {
        StringBuilder text = new StringBuilder()
                .append("카테고리: ").append(oneLine(source.categoryName())).append('\n')
                .append("상품명: ").append(oneLine(source.name())).append('\n')
                .append("설명: ").append(head(oneLine(source.description())));
        if (!source.optionGroups().isEmpty()) {
            text.append('\n').append("옵션: ").append(source.optionGroups().stream()
                    .map(group -> oneLine(group.name()) + "(" + group.values().stream()
                            .map(DropEmbeddingText::oneLine)
                            .collect(Collectors.joining(", ")) + ")")
                    .collect(Collectors.joining(" / ")));
        }
        return text.toString();
    }

    // 형식 버전·모델·차원·입력이 모두 같으면 같은 값이다
    public static String sourceHash(String model, String text) {
        String input = VERSION + "\n" + model + "\n" + DIMENSIONS + "\n" + text;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    // 줄바꿈·연속 공백을 공백 하나로 바꿔 형식의 줄 구분을 지킨다
    private static String oneLine(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    // 서로게이트 쌍(이모지 등)을 자르지 않도록 코드 포인트 단위로 자른다
    private static String head(String value) {
        if (value.codePointCount(0, value.length()) <= DESCRIPTION_LIMIT) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, DESCRIPTION_LIMIT));
    }
}
