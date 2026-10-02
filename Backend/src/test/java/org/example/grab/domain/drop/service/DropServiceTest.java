package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.request.DropImageRequest;
import org.example.grab.domain.drop.dto.request.OptionGroupRequest;
import org.example.grab.domain.drop.dto.request.OptionRequest;
import org.example.grab.domain.drop.dto.request.OptionValueRequest;
import org.example.grab.domain.drop.dto.request.SelectionRequest;
import org.example.grab.domain.drop.dto.request.ShippingRequest;
import org.example.grab.domain.category.dto.response.CategoryResponse;
import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse;
import org.example.grab.domain.drop.dto.response.common.DropCategoryResponse;
import org.example.grab.domain.wish.WishNotice;
import org.example.grab.domain.wish.service.WishQueryService;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropImageRepository;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.supabase.SupabaseStorageClient;
import org.example.grab.global.storage.supabase.SupabaseStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DropServiceTest {

    private static final String IMAGE_URL_PREFIX =
            "https://project.supabase.co/storage/v1/object/public/drop-images/images/";

    @Mock
    private DropRepository dropRepository;

    @Mock
    private DropImageRepository dropImageRepository;

    @Mock
    private CategoryService categoryService;

    @Mock
    private WishQueryService wishQueryService;

    // imageUrl 검증은 실제 공개 URL 조립이 필요하므로 진짜 클라이언트를 쓴다(HTTP 호출은 하지 않는다).
    private final SupabaseStorageClient supabaseStorageClient = new SupabaseStorageClient(
            RestClient.builder(),
            new SupabaseStorageProperties("https://project.supabase.co", "service-role-key", "drop-images",
                    Duration.ofSeconds(3), Duration.ofSeconds(5)));

    private DropService dropService;

    @BeforeEach
    void setUp() {
        dropService = new DropService(
                dropRepository, dropImageRepository, categoryService, wishQueryService, supabaseStorageClient);
    }

    @Test
    @DisplayName("임시 저장 시 옵션 그룹·값·SKU 매핑이 요청대로 조립된다")
    void createDraft_assemblesOptions() {
        // given
        given(dropRepository.save(any(Drop.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(categoryService.isActive(1L)).willReturn(true);
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
    @DisplayName("존재하지 않는 DROP 공개는 DROP_NOT_FOUND")
    void publish_notFound() {
        // given
        given(dropRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> dropService.publish(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("WISH 등록·취소 판정에서 존재하지 않는 DROP은 DROP_NOT_FOUND")
    void validateWishable_notFound() {
        // given
        given(dropRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> dropService.validateWishable(99L, OffsetDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("WISH 등록·취소 판정은 DRAFT를 DROP_NOT_FOUND로 숨긴다")
    void validateWishable_hidesDraft() {
        // given
        Drop drop = Drop.createDraft(1L);
        given(dropRepository.findById(10L)).willReturn(Optional.of(drop));

        // when & then
        assertThatThrownBy(() -> dropService.validateWishable(10L, OffsetDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 판매자의 DROP 공개는 상태와 무관하게 DROP_ACCESS_DENIED")
    void publish_accessDeniedBeforeStateCheck() {
        // given
        Drop drop = Drop.createDraft(1L);
        ReflectionTestUtils.setField(drop, "status", DropStatus.WISH);
        given(dropRepository.findByIdForUpdate(10L)).willReturn(Optional.of(drop));

        // when & then
        assertThatThrownBy(() -> dropService.publish(2L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_ACCESS_DENIED);
    }

    @Test
    @DisplayName("존재하지 않는 DROP 수정은 DROP_NOT_FOUND")
    void updateDraft_notFound() {
        // given
        given(dropRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

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
        given(dropRepository.findByIdForUpdate(10L)).willReturn(Optional.of(drop));

        // when & then
        assertThatThrownBy(() -> dropService.updateDraft(2L, 10L, emptyRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_ACCESS_DENIED);
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

    @Test
    @DisplayName("DRAFT 상태가 아닌 DROP 수정은 카테고리 검증보다 DROP_NOT_EDITABLE이 우선한다")
    void updateDraft_notEditable() {
        // given
        Drop drop = Drop.createDraft(1L);
        ReflectionTestUtils.setField(drop, "status", DropStatus.WISH);
        given(dropRepository.findByIdForUpdate(10L)).willReturn(Optional.of(drop));
        DropDraftRequest request = new DropDraftRequest(
                null, null, null, 999L, null, null, null, null, null);

        // when & then
        assertThatThrownBy(() -> dropService.updateDraft(1L, 10L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_EDITABLE);
        verifyNoInteractions(categoryService);
    }

    @Test
    @DisplayName("없거나 비활성인 카테고리로 임시 저장하면 VALIDATION_FAILED와 categoryId 필드 오류를 반환한다")
    void createDraft_rejectsInactiveCategory() {
        // given
        given(categoryService.isActive(999L)).willReturn(false);
        DropDraftRequest request = new DropDraftRequest(
                null, null, null, 999L, null, null, null, null, null);

        // when & then
        assertThatThrownBy(() -> dropService.createDraft(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field)
                            .containsExactly("categoryId");
                });
    }

    @Test
    @DisplayName("categoryId가 null인 부분 수정은 카테고리 검증을 호출하지 않는다")
    void updateDraft_skipsCategoryValidationWhenCategoryIdIsNull() {
        // given
        Drop drop = Drop.createDraft(1L);
        given(dropRepository.findByIdForUpdate(10L)).willReturn(Optional.of(drop));

        // when
        dropService.updateDraft(1L, 10L, emptyRequest());

        // then
        verifyNoInteractions(categoryService);
    }

    @Test
    @DisplayName("공개 시 카테고리가 비활성이면 VALIDATION_FAILED로 거부된다")
    void publish_rejectsInactiveCategory() {
        // given
        Drop drop = Drop.createDraft(1L);
        drop.updateDraft("상품", "설명", 1L, 3000L, "안내",
                OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(2));
        drop.addImage(DropImage.create(drop, UUID.randomUUID(), "https://example.com/a.jpg", 0, "상품"));
        drop.addOption(DropOption.create(drop, 1000L, 5, 0));
        given(dropRepository.findByIdForUpdate(10L)).willReturn(Optional.of(drop));
        given(categoryService.isActive(1L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> dropService.publish(1L, 10L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field)
                            .containsExactly("categoryId");
                });
    }

    @Test
    @DisplayName("존재하지 않는 DROP 상세 조회는 DROP_NOT_FOUND")
    void findSellerDrop_notFound() {
        // given
        given(dropRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> dropService.findSellerDrop(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 판매자의 DROP 상세 조회는 DROP_ACCESS_DENIED")
    void findSellerDrop_accessDenied() {
        // given
        Drop drop = Drop.createDraft(1L);
        given(dropRepository.findById(10L)).willReturn(Optional.of(drop));

        // when & then
        assertThatThrownBy(() -> dropService.findSellerDrop(2L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_ACCESS_DENIED);
    }

    @Test
    @DisplayName("공개 상세: 없는 DROP은 DROP_NOT_FOUND")
    void findPublicDrop_notFound() {
        // given
        given(dropRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> dropService.findPublicDrop(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("공개 상세: DRAFT는 DROP_NOT_FOUND로 숨기고 이후 조회를 하지 않는다")
    void findPublicDrop_hidesDraft() {
        // given
        Drop drop = Drop.createDraft(1L);
        given(dropRepository.findById(10L)).willReturn(Optional.of(drop));

        // when & then
        assertThatThrownBy(() -> dropService.findPublicDrop(10L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_FOUND);
        verifyNoInteractions(categoryService, wishQueryService);
    }

    @Test
    @DisplayName("공개 상세: CANCELED는 200 응답을 만들고 actions가 모두 false다")
    void findPublicDrop_allowsCanceled() {
        // given
        Drop drop = Drop.createDraft(1L);
        ReflectionTestUtils.setField(drop, "status", DropStatus.CANCELED);
        ReflectionTestUtils.setField(drop, "categoryId", 1L);
        given(dropRepository.findById(10L)).willReturn(Optional.of(drop));
        given(categoryService.findCategory(1L))
                .willReturn(Optional.of(new CategoryResponse(1L, "FASHION", "패션")));
        given(wishQueryService.countActiveByDropId(10L)).willReturn(3L);

        // when
        PublicDropDetailResponse response = dropService.findPublicDrop(10L);

        // then
        assertThat(response.status()).isEqualTo(DropStatus.CANCELED);
        assertThat(response.category()).isEqualTo(new DropCategoryResponse(1L, "패션"));
        assertThat(response.wishCount()).isEqualTo(3L);
        assertThat(response.wishNotice()).isEqualTo(WishNotice.MESSAGE);
        assertThat(response.actions())
                .isEqualTo(new PublicDropDetailResponse.Actions(false, false, false));
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

        return new DropDraftRequest("상품", "설명", List.of(image(UUID.randomUUID())), 1L, null, null,
                new ShippingRequest(3000L, "안내"), List.of(material, length), List.of(first, second));
    }

    private DropDraftRequest emptyRequest() {
        return new DropDraftRequest(null, null, null, null, null, null, null, null, null);
    }

    private static DropImageRequest image(UUID imageId) {
        return new DropImageRequest(imageId, IMAGE_URL_PREFIX + imageId + ".jpg");
    }

    @Test
    @DisplayName("imageUrl이 imageId와 맞지 않으면 VALIDATION_FAILED(images[0].imageUrl)이고 저장하지 않는다")
    void createDraft_rejectsMismatchedImageUrl() {
        // given
        UUID imageId = UUID.randomUUID();
        DropDraftRequest request = new DropDraftRequest(
                null, null, List.of(new DropImageRequest(imageId, "https://evil.example.com/other.jpg")),
                null, null, null, null, null, null);

        // when & then
        assertThatThrownBy(() -> dropService.createDraft(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field)
                            .containsExactly("images[0].imageUrl");
                });
        verifyNoInteractions(categoryService);
    }

    @Test
    @DisplayName("같은 요청 안에서 imageId가 중복되면 VALIDATION_FAILED(images[1].imageId)")
    void createDraft_rejectsDuplicateImageId() {
        // given
        UUID imageId = UUID.randomUUID();
        DropDraftRequest request = new DropDraftRequest(
                null, null, List.of(image(imageId), image(imageId)),
                null, null, null, null, null, null);

        // when & then
        assertThatThrownBy(() -> dropService.createDraft(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field)
                            .containsExactly("images[1].imageId");
                });
    }

    @Test
    @DisplayName("다른 DROP이 쓰는 imageId는 VALIDATION_FAILED(images[0].imageId)이고 저장하지 않는다")
    void createDraft_rejectsImageIdUsedByOtherDrop() {
        // given
        UUID imageId = UUID.randomUUID();
        DropDraftRequest request = new DropDraftRequest(
                null, null, List.of(image(imageId)), null, null, null, null, null, null);
        given(dropImageRepository.findUuidsUsedByOtherDrop(Set.of(imageId), null)).willReturn(Set.of(imageId));

        // when & then
        assertThatThrownBy(() -> dropService.createDraft(1L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field)
                            .containsExactly("images[0].imageId");
                });
        verify(dropRepository, never()).save(any(Drop.class));
    }

    @Test
    @DisplayName("같은 DROP 수정에서 자기 imageId를 다시 보내면 다른 DROP 조회에서 제외되어 통과한다")
    void updateDraft_allowsOwnImageId() {
        // given
        Drop drop = Drop.createDraft(1L);
        ReflectionTestUtils.setField(drop, "id", 10L);
        given(dropRepository.findByIdForUpdate(10L)).willReturn(Optional.of(drop));
        UUID imageId = UUID.randomUUID();
        DropDraftRequest request = new DropDraftRequest(
                null, null, List.of(image(imageId)), null, null, null, null, null, null);
        given(dropImageRepository.findUuidsUsedByOtherDrop(eq(Set.of(imageId)), anyLong())).willReturn(Set.of());

        // when
        dropService.updateDraft(1L, 10L, request);

        // then
        assertThat(drop.getImages()).hasSize(1);
    }

    @Test
    @DisplayName("여러 imageId의 다른 DROP 사용 여부를 한 번에 조회한다")
    void createDraft_checksImageIdsInSingleQuery() {
        // given
        UUID firstImageId = UUID.randomUUID();
        UUID secondImageId = UUID.randomUUID();
        DropDraftRequest request = new DropDraftRequest(
                null, null, List.of(image(firstImageId), image(secondImageId)),
                null, null, null, null, null, null);

        // when
        dropService.createDraft(1L, request);

        // then
        verify(dropImageRepository).findUuidsUsedByOtherDrop(Set.of(firstImageId, secondImageId), null);
    }

    @Test
    @DisplayName("images를 생략하면 기존 이미지를 유지하고 imageId 중복 조회를 하지 않는다")
    void updateDraft_keepsImagesWhenOmitted() {
        // given
        Drop drop = Drop.createDraft(1L);
        ReflectionTestUtils.setField(drop, "id", 10L);
        drop.addImage(DropImage.create(drop, UUID.randomUUID(), "old.jpg", 0, "상품"));
        given(dropRepository.findByIdForUpdate(10L)).willReturn(Optional.of(drop));

        // when
        dropService.updateDraft(1L, 10L, emptyRequest());

        // then
        assertThat(drop.getImages()).hasSize(1);
        verifyNoInteractions(dropImageRepository);
    }
}
