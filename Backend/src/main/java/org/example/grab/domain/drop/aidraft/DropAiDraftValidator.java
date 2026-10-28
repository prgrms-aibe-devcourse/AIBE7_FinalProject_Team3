package org.example.grab.domain.drop.aidraft;

import org.example.grab.domain.category.dto.response.CategoryResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/*
    LLM 응답을 항목별로 검증한다(DROP.md 2.8). 한 항목이 어긋나면 그 항목만 버리고 나머지는 판매자에게 보여 준다.
    카테고리는 활성 목록의 코드일 때만 ID로 바꾸고, 대체 설명은 사진 수와 개수가 맞을 때만 쓴다.
    JSON이 아니면 초안 전체를 버린다.
 */
public final class DropAiDraftValidator {

    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_DESCRIPTION_LENGTH = 1000;
    static final int MAX_ALT_TEXT_LENGTH = 300;

    private DropAiDraftValidator() {
    }

    public record Draft(String name, String description, Long categoryId, List<String> altTexts) {

        public boolean empty() {
            return name == null && description == null && categoryId == null && altTexts == null;
        }

        public boolean complete() {
            return name != null && description != null && categoryId != null && altTexts != null;
        }
    }

    public static Optional<Draft> validate(String response, int imageCount, List<CategoryResponse> categories,
                                           ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new Draft(
                text(root.get("name"), MAX_NAME_LENGTH),
                text(root.get("description"), MAX_DESCRIPTION_LENGTH),
                category(root.get("categoryCode"), categories),
                altTexts(root.get("altTexts"), imageCount)));
    }

    private static String text(JsonNode node, int maxLength) {
        if (node == null || !node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() || value.length() > maxLength ? null : value;
    }

    private static Long category(JsonNode node, List<CategoryResponse> categories) {
        if (node == null || !node.isString()) {
            return null;
        }
        String code = node.asString().strip();
        return categories.stream()
                .filter(category -> category.code().equals(code))
                .map(CategoryResponse::categoryId)
                .findFirst()
                .orElse(null);
    }

    private static List<String> altTexts(JsonNode node, int imageCount) {
        if (node == null || !node.isArray() || node.size() != imageCount) {
            return null;
        }
        List<String> altTexts = new ArrayList<>();
        for (JsonNode item : node) {
            String altText = text(item, MAX_ALT_TEXT_LENGTH);
            if (altText == null) {
                return null;
            }
            altTexts.add(altText);
        }
        return List.copyOf(altTexts);
    }
}
