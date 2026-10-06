package org.example.grab.domain.drop.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DropDraftRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("모든 필드가 null이어도 임시 저장 요청은 유효하다")
    void allowsEmptyDraft() {
        // given
        DropDraftRequest request = new DropDraftRequest(null, null, null, null, null, null, null, null, null);

        // when & then
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("옵션 그룹의 키·이름이 비면 검증에 실패한다")
    void rejectsBlankGroupKeyAndName() {
        // given
        OptionGroupRequest group = new OptionGroupRequest("", "", null, List.of());
        DropDraftRequest request = new DropDraftRequest(null, null, null, null, null, null, null, List.of(group), null);

        // when & then
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("SKU의 가격·수량이 없으면 검증에 실패한다")
    void rejectsMissingOptionPriceAndQuantity() {
        // given
        OptionRequest option = new OptionRequest(List.of(), null, null, true, 0);
        DropDraftRequest request = new DropDraftRequest(null, null, null, null, null, null, null, null, List.of(option));

        // when & then
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("이미지 URL이 500자를 넘거나 imageId가 없으면 검증에 실패한다")
    void rejectsInvalidImageRequest() {
        // imageUrl 501자
        String longUrl = "x".repeat(501);
        DropDraftRequest longUrlRequest = new DropDraftRequest(null, null,
                List.of(new DropImageRequest(UUID.randomUUID(), longUrl)), null, null, null, null, null, null);
        assertThat(validator.validate(longUrlRequest)).isNotEmpty();

        // imageId null
        DropDraftRequest nullIdRequest = new DropDraftRequest(null, null,
                List.of(new DropImageRequest(null, "https://example.com/a.jpg")), null, null, null, null, null, null);
        assertThat(validator.validate(nullIdRequest)).isNotEmpty();
    }

    @Test
    @DisplayName("images 배열의 null 원소는 검증에 실패한다")
    void rejectsNullImageElement() {
        // given
        DropDraftRequest request = new DropDraftRequest(
                null, null, singleNullElement(), null, null, null, null, null, null);

        // when & then
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("상품 이미지는 10개를 초과하면 검증에 실패한다")
    void rejectsMoreThanTenImages() {
        // given
        DropImageRequest image = new DropImageRequest(UUID.randomUUID(), "https://example.com/a.jpg");
        DropDraftRequest request = new DropDraftRequest(
                null, null, java.util.Collections.nCopies(11, image), null, null, null, null, null, null);

        // when & then
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("중첩 배열의 null 원소는 모두 검증에 실패한다")
    void rejectsNullElementsInNestedArrays() {
        // optionGroups: [null]
        assertThat(validator.validate(new DropDraftRequest(
                null, null, null, null, null, null, null, singleNullElement(), null)))
                .isNotEmpty();
        // options: [null]
        assertThat(validator.validate(new DropDraftRequest(
                null, null, null, null, null, null, null, null, singleNullElement())))
                .isNotEmpty();
        // values: [null]
        OptionGroupRequest groupWithNullValueElement = new OptionGroupRequest(
                "material", "소재", 0, java.util.Collections.singletonList(null));
        assertThat(validator.validate(new DropDraftRequest(
                null, null, null, null, null, null, null, List.of(groupWithNullValueElement), null)))
                .isNotEmpty();
        // selections: [null]
        OptionRequest optionWithNullSelection = new OptionRequest(
                java.util.Collections.singletonList(null), 1000L, 5, true, 0);
        assertThat(validator.validate(new DropDraftRequest(
                null, null, null, null, null, null, null, null, List.of(optionWithNullSelection))))
                .isNotEmpty();
    }

    private static <T> List<T> singleNullElement() {
        java.util.List<T> list = new java.util.ArrayList<>();
        list.add(null);
        return list;
    }

    @Test
    @DisplayName("유효한 요청은 검증을 통과한다")
    void acceptsValidRequest() {
        // given
        OptionValueRequest value = new OptionValueRequest("cotton", "코튼", 0);
        OptionGroupRequest group = new OptionGroupRequest("material", "소재", 0, List.of(value));
        SelectionRequest selection = new SelectionRequest("material", "cotton");
        OptionRequest option = new OptionRequest(List.of(selection), 129000L, 10, true, 0);
        DropDraftRequest request = new DropDraftRequest(
                "상품", "설명", List.of(new DropImageRequest(UUID.randomUUID(), "https://example.com/a.jpg")),
                1L, null, null, new ShippingRequest(3000L, "안내"), List.of(group), List.of(option));

        // when & then
        assertThat(validator.validate(request)).isEmpty();
    }
}
