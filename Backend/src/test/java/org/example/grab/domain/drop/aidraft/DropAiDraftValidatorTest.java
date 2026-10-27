package org.example.grab.domain.drop.aidraft;

import org.example.grab.domain.category.dto.response.CategoryResponse;
import org.example.grab.domain.drop.aidraft.DropAiDraftValidator.Draft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DropAiDraftValidatorTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final List<CategoryResponse> CATEGORIES = List.of(
            new CategoryResponse(1L, "FASHION", "패션"), new CategoryResponse(6L, "LIVING", "리빙"));

    @Test
    @DisplayName("형식에 맞는 응답은 카테고리 코드를 ID로 바꾸고 앞뒤 공백을 지운다")
    void validate_acceptsCompleteDraft() {
        String response = """
                {"name": " 블랙 니트 가디건 ", "description": "도톰한 블랙 니트 가디건입니다.",
                 "categoryCode": "FASHION", "altTexts": ["가디건 정면", "가디건 뒷면"]}
                """;

        Draft draft = DropAiDraftValidator.validate(response, 2, CATEGORIES, OBJECT_MAPPER).orElseThrow();

        assertThat(draft).isEqualTo(new Draft("블랙 니트 가디건", "도톰한 블랙 니트 가디건입니다.", 1L,
                List.of("가디건 정면", "가디건 뒷면")));
        assertThat(draft.complete()).isTrue();
    }

    @Test
    @DisplayName("목록에 없는 카테고리, 사진 수와 다른 대체 설명, 길이를 넘는 항목은 그 항목만 버린다")
    void validate_dropsInvalidFieldsOnly() {
        String response = """
                {"name": "%s", "description": "설명입니다.", "categoryCode": "WEAPON", "altTexts": ["한 장뿐"]}
                """.formatted("가".repeat(DropAiDraftValidator.MAX_NAME_LENGTH + 1));

        Draft draft = DropAiDraftValidator.validate(response, 2, CATEGORIES, OBJECT_MAPPER).orElseThrow();

        assertThat(draft.name()).isNull();
        assertThat(draft.description()).isEqualTo("설명입니다.");
        assertThat(draft.categoryId()).isNull();
        assertThat(draft.altTexts()).isNull();
        assertThat(draft.complete()).isFalse();
        assertThat(draft.empty()).isFalse();
    }

    @Test
    @DisplayName("JSON이 아니면 초안 전체를 버리고, 모든 항목이 null이면 빈 초안이다")
    void validate_rejectsNonJsonAndDetectsEmpty() {
        assertThat(DropAiDraftValidator.validate("상품명: 가디건", 1, CATEGORIES, OBJECT_MAPPER)).isEmpty();
        assertThat(DropAiDraftValidator.validate("[]", 1, CATEGORIES, OBJECT_MAPPER)).isEmpty();
        assertThat(DropAiDraftValidator.validate(
                "{\"name\": null, \"description\": \" \", \"categoryCode\": null, \"altTexts\": [\"\"]}",
                1, CATEGORIES, OBJECT_MAPPER).orElseThrow().empty()).isTrue();
    }
}
