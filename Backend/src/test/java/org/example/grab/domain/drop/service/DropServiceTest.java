package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.request.OptionGroupRequest;
import org.example.grab.domain.drop.dto.request.OptionRequest;
import org.example.grab.domain.drop.dto.request.OptionValueRequest;
import org.example.grab.domain.drop.dto.request.SelectionRequest;
import org.example.grab.domain.drop.dto.request.ShippingRequest;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class DropServiceTest {

    @Mock
    private DropRepository dropRepository;

    @InjectMocks
    private DropService dropService;

    @Test
    @DisplayName("임시 저장 시 옵션 그룹·값·SKU 매핑이 요청대로 조립된다")
    void createDraft_assemblesOptions() {
        // given
        given(dropRepository.save(any(Drop.class))).willAnswer(invocation -> invocation.getArgument(0));
        DropDraftRequest request = requestWithOptions();

        // when
        Drop drop = dropService.createDraft(1L, request);

        // then
        assertThat(drop.getStatus()).isEqualTo(DropStatus.DRAFT);
        assertThat(drop.getImages()).hasSize(1);
        assertThat(drop.getOptionGroups()).hasSize(2);
        assertThat(drop.getOptions()).hasSize(2);

        DropOption first = drop.getOptions().get(0);
        assertThat(first.getValueMaps()).hasSize(2);
        assertThat(first.getAvailableQuantity()).isEqualTo(10);
        assertThat(drop.getOptions().get(1).isActive()).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 valueKey를 참조하면 INVALID_OPTION_COMBINATION")
    void createDraft_rejectsUnknownValueKey() {
        // given
        DropDraftRequest request = new DropDraftRequest(
                null, null, null, null, null, null, null,
                List.of(new OptionGroupRequest("material", "소재", 0,
                        List.of(new OptionValueRequest("cotton", "코튼", 0)))),
                List.of(new OptionRequest(
                        List.of(new SelectionRequest("material", "linen")), 1000L, 5, true, 0)));

        // when & then
        assertThatThrownBy(() -> dropService.createDraft(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.INVALID_OPTION_COMBINATION);
    }

    @Test
    @DisplayName("한 SKU가 같은 그룹을 두 번 선택하면 INVALID_OPTION_COMBINATION")
    void createDraft_rejectsSameGroupTwice() {
        // given
        DropDraftRequest request = new DropDraftRequest(
                null, null, null, null, null, null, null,
                List.of(new OptionGroupRequest("material", "소재", 0,
                        List.of(new OptionValueRequest("cotton", "코튼", 0),
                                new OptionValueRequest("linen", "린넨", 1)))),
                List.of(new OptionRequest(
                        List.of(new SelectionRequest("material", "cotton"),
                                new SelectionRequest("material", "linen")), 1000L, 5, true, 0)));

        // when & then
        assertThatThrownBy(() -> dropService.createDraft(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.INVALID_OPTION_COMBINATION);
    }

    @Test
    @DisplayName("존재하지 않는 DROP 수정은 DROP_NOT_FOUND")
    void updateDraft_notFound() {
        // given
        given(dropRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> dropService.updateDraft(1L, 99L, emptyRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 판매자의 DROP 수정은 DROP_ACCESS_DENIED")
    void updateDraft_accessDenied() {
        // given
        Drop drop = Drop.createDraft(1L);
        given(dropRepository.findById(10L)).willReturn(Optional.of(drop));

        // when & then
        assertThatThrownBy(() -> dropService.updateDraft(2L, 10L, emptyRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_ACCESS_DENIED);
    }

    @Test
    @DisplayName("DRAFT 상태가 아닌 DROP 수정은 DROP_NOT_EDITABLE")
    void updateDraft_notEditable() {
        // given
        Drop drop = Drop.createDraft(1L);
        ReflectionTestUtils.setField(drop, "status", DropStatus.WISH);
        given(dropRepository.findById(10L)).willReturn(Optional.of(drop));

        // when & then
        assertThatThrownBy(() -> dropService.updateDraft(1L, 10L, emptyRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_EDITABLE);
    }

    @Test
    @DisplayName("optionGroups만 보내면 INVALID_OPTION_COMBINATION")
    void createDraft_rejectsGroupsWithoutOptions() {
        // given
        DropDraftRequest request = new DropDraftRequest(
                null, null, null, null, null, null, null,
                List.of(new OptionGroupRequest("material", "소재", 0,
                        List.of(new OptionValueRequest("cotton", "코튼", 0)))),
                null);

        // when & then
        assertThatThrownBy(() -> dropService.createDraft(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.INVALID_OPTION_COMBINATION);
    }

    @Test
    @DisplayName("options만 보내면 INVALID_OPTION_COMBINATION")
    void createDraft_rejectsOptionsWithoutGroups() {
        // given
        DropDraftRequest request = new DropDraftRequest(
                null, null, null, null, null, null, null,
                null,
                List.of(new OptionRequest(
                        List.of(new SelectionRequest("material", "cotton")), 1000L, 5, true, 0)));

        // when & then
        assertThatThrownBy(() -> dropService.createDraft(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.INVALID_OPTION_COMBINATION);
    }

    private DropDraftRequest requestWithOptions() {
        OptionGroupRequest material = new OptionGroupRequest("material", "소재", 0,
                List.of(new OptionValueRequest("cotton", "코튼", 0),
                        new OptionValueRequest("linen", "린넨", 1)));
        OptionGroupRequest length = new OptionGroupRequest("length", "길이", 1,
                List.of(new OptionValueRequest("short", "숏", 0),
                        new OptionValueRequest("long", "롱", 1)));

        OptionRequest first = new OptionRequest(
                List.of(new SelectionRequest("material", "cotton"), new SelectionRequest("length", "short")),
                129000L, 10, true, 0);
        OptionRequest second = new OptionRequest(
                List.of(new SelectionRequest("material", "linen"), new SelectionRequest("length", "long")),
                139000L, 5, false, 1);

        return new DropDraftRequest("상품", "설명", List.of("https://example.com/a.jpg"), 1L, null, null,
                new ShippingRequest(3000L, "안내"), List.of(material, length), List.of(first, second));
    }

    private DropDraftRequest emptyRequest() {
        return new DropDraftRequest(null, null, null, null, null, null, null, null, null);
    }
}
