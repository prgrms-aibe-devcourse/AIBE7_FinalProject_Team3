package org.example.grab.domain.drop.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    @DisplayName("이미지 URL이 500자를 넘으면 검증에 실패한다")
    void rejectsLongImageUrl() {
        // given
        String longUrl = "x".repeat(501);
        DropDraftRequest request = new DropDraftRequest(null, null, List.of(longUrl), null, null, null, null, null, null);

        // when & then
        assertThat(validator.validate(request)).isNotEmpty();
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
                "상품", "설명", List.of("https://example.com/a.jpg"), 1L, null, null,
                new ShippingRequest(3000L, "안내"), List.of(group), List.of(option));

        // when & then
        assertThat(validator.validate(request)).isEmpty();
    }
}
