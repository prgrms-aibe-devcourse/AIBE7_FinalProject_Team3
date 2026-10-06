package org.example.grab.domain.drop.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.dto.PublicDropListProjection;
import org.example.grab.domain.drop.dto.PublicDropSort;
import org.example.grab.domain.drop.dto.SellerDropListProjection;
import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.request.DropImageRequest;
import org.example.grab.domain.drop.dto.request.ShippingRequest;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.dto.response.PublicDropStockResponse;
import org.example.grab.domain.drop.dto.response.SellerDropStockResponse;
import org.example.grab.domain.drop.dto.response.common.DropCategoryResponse;
import org.example.grab.domain.drop.dto.response.SellerDropDetailResponse;
import org.example.grab.domain.drop.dto.response.SellerDropListResponse;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropImageRepository;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.wish.service.WishQueryService;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.supabase.SupabaseStorageClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 판매자의 DROP 임시 저장·수정·공개·조회를 담당한다.
 * 모든 변경은 DRAFT 상태에서만 허용하며, 공개 전 필수 항목 검증은 공개(publish) 단계의 책임이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DropService {

    private static final List<String> PUBLIC_STATUSES = List.of(
            DropStatus.WISH.name(), DropStatus.GRAB.name(), DropStatus.ENDED.name());
    private static final int KEYWORD_MAX_LENGTH = 100;
    private static final String IMAGE_OBJECT_KEY_PREFIX = "images/";
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "png", "webp");

    private final DropRepository dropRepository;
    private final DropImageRepository dropImageRepository;
    private final CategoryService categoryService;
    private final WishQueryService wishQueryService;
    private final SupabaseStorageClient supabaseStorageClient;

    /** 새 DRAFT를 만들고 요청 값을 반영해 저장한다. */
    @Transactional
    public Drop createDraft(Long sellerId, DropDraftRequest request) {
        Drop drop = Drop.createDraft(sellerId);
        applyDraft(drop, request);
        return dropRepository.save(drop);
    }

    /**
     * DRAFT를 수정한다. 조회 → 소유권 → 상태 순서로 검증해,
     * 존재하지 않음(404)을 먼저 확정한 뒤 권한(403)과 편집 가능 상태(409)를 판단한다.
     * 행 잠금으로 공개와 직렬화해, 공개 검증 뒤 자식이 바뀌는 경합을 막는다(GR-64 R03).
     */
    @Transactional
    public Drop updateDraft(Long sellerId, Long dropId, DropDraftRequest request) {
        Drop drop = findOwnedDropForUpdate(sellerId, dropId);
        applyDraft(drop, request);
        return drop;
    }

    /**
     * DRAFT를 WISH로 공개한다. 수정과 같은 순서(조회 → 소유권)로 검증한 뒤 공개 검증은 도메인에 맡긴다.
     * 행 잠금으로 수정과 직렬화하고, 공개 시각은 잠금을 얻은 뒤 생성한다(GR-64 R03).
     */
    @Transactional
    public Drop publish(Long sellerId, Long dropId) {
        Drop drop = findOwnedDropForUpdate(sellerId, dropId);
        drop.publish(OffsetDateTime.now(ZoneOffset.UTC));
        // 공개 이후 카테고리 활성 여부를 마지막으로 확인한다. 실패하면 트랜잭션 롤백으로 WISH 전환이 취소된다.
        validateCategory(drop.getCategoryId());
        return drop;
    }

    /**
     * 판매자가 공개한 WISH를 취소한다(GR-18).
     * 조회 → 소유권 순서로 검증한 뒤 DROP 행 잠금을 획득하고, 잠금 이후에 now를 생성해 취소 가능 여부를 판정한다.
     * 잠금 대기 중에 판매가 시작되면 취소가 거부되어야 하므로 잠금 전 시각으로 판정하지 않는다.
     */
    @Transactional
    public Drop cancel(Long sellerId, Long dropId, String reason) {
        Drop drop = findOwnedDropForUpdate(sellerId, dropId);
        drop.cancel(reason, OffsetDateTime.now(ZoneOffset.UTC));
        return drop;
    }

    /**
     * WISH 등록·취소 가능 여부를 판정한다. 등록과 취소의 규칙이 같아 하나의 메서드로 공유한다.
     * 존재하지 않거나 DRAFT인 DROP은 DROP_NOT_FOUND로 숨기고, 그 밖의 불가 상태는 도메인이 판정한다.
     */
    public void validateWishable(Long dropId, OffsetDateTime now) {
        Drop drop = findDrop(dropId);
        drop.validateWishable(now);
    }

    // 목록은 조회 전용 트랜잭션에서 쿼리 한 번으로 가져오고, 프로젝션을 응답 DTO로 변환한다.
    public PageResponse<SellerDropListResponse> findSellerDrops(
            Long sellerId, DropStatus status, int page, int size) {
        Page<SellerDropListProjection> drops = dropRepository.findSellerDrops(
                sellerId, status, PageRequest.of(page, size));
        return PageResponse.from(drops.map(SellerDropListResponse::from));
    }

    /*
     * 공개 DROP 목록. DRAFT·CANCELED는 어떤 경우에도 제외한다(DISC-001).
     * status가 없으면 WISH·GRAB·ENDED를 모두, 있으면 그 상태만 조회한다.
     * 정렬은 허용 속성을 컬럼명으로 매핑한 Sort를 쓰고, keyword는 trim·이스케이프해 넘긴다.
     */
    public PageResponse<PublicDropListResponse> findPublicDrops(
            DropStatus status, Long categoryId, String keyword, Boolean soldOut,
            PublicDropSort sort, int page, int size) {
        List<String> statuses = resolvePublicStatuses(status);
        PublicDropSort effectiveSort = sort != null ? sort : PublicDropSort.parse(null);
        Page<PublicDropListProjection> drops = dropRepository.findPublicDrops(
                statuses, categoryId, normalizeKeyword(keyword), soldOut,
                PageRequest.of(page, size, effectiveSort.toSort()));
        return PageResponse.from(drops.map(PublicDropListResponse::from));
    }

    public SellerDropDetailResponse findSellerDrop(Long sellerId, Long dropId) {
        return SellerDropDetailResponse.from(findOwnedDrop(sellerId, dropId));
    }

    /**
     * 공개 DROP 상세. 비로그인 조회이며 DRAFT는 존재를 숨겨 DROP_NOT_FOUND로 응답한다.
     * CANCELED는 0절 확정대로 200으로 보여 주고 actions가 모두 false가 된다.
     * now는 actions 판정과 응답 serverTime에 같은 값을 쓴다.
     */
    public PublicDropDetailResponse findPublicDrop(Long dropId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Drop drop = findDrop(dropId);
        if (!drop.isPublic()) {
            throw new BusinessException(DropErrorCode.DROP_NOT_FOUND);
        }
        long wishCount = wishQueryService.countActiveByDropId(dropId);
        return PublicDropDetailResponse.of(drop, toCategory(drop.getCategoryId()), wishCount, now);
    }

    /**
     * 판매자 재고 현황(2.7). 조회 → 소유권 검증 후 PostgreSQL에 커밋된 수량을 응답한다.
     * 상태 제한이 없어 소유자면 DRAFT도 조회할 수 있고, 비활성 SKU도 포함한다(정책 합의).
     */
    public SellerDropStockResponse findSellerStocks(Long sellerId, Long dropId) {
        return SellerDropStockResponse.from(findOwnedDrop(sellerId, dropId));
    }

    /**
     * 공개 재고 재조회(1.2). 공개 상세와 같은 isPublic() 기준을 적용해 DRAFT는 DROP_NOT_FOUND로 숨긴다.
     * CANCELED여도 가용 재고를 0으로 바꾸지 않는다. now는 응답 serverTime에 쓴다.
     */
    public PublicDropStockResponse findPublicStocks(Long dropId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Drop drop = findDrop(dropId);
        if (!drop.isPublic()) {
            throw new BusinessException(DropErrorCode.DROP_NOT_FOUND);
        }
        return PublicDropStockResponse.of(drop, now);
    }

    // category_id는 NOT NULL·FK라 정상 데이터에서는 항상 존재한다. 정합성이 깨진 경우 DROP_NOT_FOUND로 숨긴다.
    private DropCategoryResponse toCategory(Long categoryId) {
        return categoryService.findCategory(categoryId)
                .map(category -> new DropCategoryResponse(category.categoryId(), category.name()))
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
    }

    // status가 없으면 공개 상태 전체, DRAFT·CANCELED는 공개 목록에 넣을 수 없으므로 거부한다.
    private List<String> resolvePublicStatuses(DropStatus status) {
        if (status == null) {
            return PUBLIC_STATUSES;
        }
        if (status == DropStatus.DRAFT || status == DropStatus.CANCELED) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return List.of(status.name());
    }

    // 앞뒤 공백을 제거하고, 비어 있으면 조건을 무시(null)한다. %·_·\는 와일드카드가 아니라 리터럴로 만든다.
    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > KEYWORD_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        String escaped = trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private Drop findOwnedDrop(Long sellerId, Long dropId) {
        Drop drop = findDrop(dropId);
        drop.validateOwner(sellerId);
        return drop;
    }

    // 수정·공개·취소는 행 잠금이 필요하므로 findByIdForUpdate로 조회한다. 존재 확인 → 소유권 순서는 findOwnedDrop과 같다.
    private Drop findOwnedDropForUpdate(Long sellerId, Long dropId) {
        Drop drop = dropRepository.findByIdForUpdate(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        drop.validateOwner(sellerId);
        return drop;
    }

    private Drop findDrop(Long dropId) {
        return dropRepository.findById(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
    }

    private void applyDraft(Drop drop, DropDraftRequest request) {
        // null 필드는 "변경하지 않음"으로 해석한다(부분 수정).
        ShippingRequest shipping = request.shipping();
        drop.updateDraft(
                request.name(),
                request.description(),
                request.categoryId(),
                shipping != null ? shipping.shippingFee() : null,
                shipping != null ? shipping.shippingNotice() : null,
                request.saleStartsAt(),
                request.saleEndsAt());
        // 상태(DROP_NOT_EDITABLE)·일정 검증 다음에 카테고리를 확인해 오류 우선순위를 유지한다.
        validateCategory(request.categoryId());

        // 그룹만 또는 옵션만 오면 기존 SKU가 조용히 삭제되므로, 옵션 구조는 둘 다 오거나 둘 다 없어야 한다.
        boolean replaceImages = request.images() != null;
        boolean hasOptionGroups = request.optionGroups() != null;
        boolean hasOptions = request.options() != null;
        if (hasOptionGroups != hasOptions) {
            throw new BusinessException(DropErrorCode.INVALID_OPTION_COMBINATION);
        }
        boolean replaceOptions = hasOptionGroups;
        if (!replaceImages && !replaceOptions) {
            return;
        }
        // 자식 컬렉션을 비우기 전에 imageId·imageUrl 관계와 다른 DROP 사용 여부를 먼저 검증한다.
        if (replaceImages) {
            validateImages(request.images(), drop.getId());
        }
        // 자식 컬렉션에는 sort_order 등 unique 제약이 있어, 삽입이 삭제보다 먼저 실행되면 충돌한다.
        // 컬렉션을 비우고 flush로 삭제를 먼저 확정한 뒤 새 자식을 추가한다(맵이 값·그룹을 참조하므로 옵션 → 그룹 순서).
        if (replaceOptions) {
            drop.clearOptions();
            dropRepository.flush();
            drop.clearOptionGroups();
            dropRepository.flush();
        }
        if (replaceImages) {
            drop.clearImages();
            dropRepository.flush();
        }

        if (replaceImages) {
            toImages(drop, request.images()).forEach(drop::addImage);
        }
        if (replaceOptions) {
            DropOptionAssembler.apply(drop, request.optionGroups(), request.options());
        }
    }

    // categoryId는 부분 수정에서 null이면 "변경하지 않음"이므로 검증하지 않는다.
    private void validateCategory(Long categoryId) {
        if (categoryId != null && !categoryService.isActive(categoryId)) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_FAILED,
                    List.of(new ErrorResponse.FieldError("categoryId", "존재하지 않거나 비활성인 카테고리입니다.")));
        }
    }

    // 이미지 정렬 순서는 요청 배열의 인덱스를 그대로 쓰고, alt_text는 정책 확정 전까지 상품명을 기본값으로 둔다.
    private List<DropImage> toImages(Drop drop, List<DropImageRequest> images) {
        String altText = drop.getName() != null ? drop.getName() : "";
        List<DropImage> result = new ArrayList<>();
        for (int index = 0; index < images.size(); index++) {
            DropImageRequest image = images.get(index);
            result.add(DropImage.create(drop, image.imageId(), image.imageUrl(), index, altText));
        }
        return result;
    }

    /*
        imageUrl을 그대로 믿지 않고 발급한 imageId와의 관계로 검증한다(GR-51 2-3).
        다른 사이트 URL이나 다른 이미지의 URL을 붙이는 것을 막고, 같은 요청·다른 DROP의 imageId 재사용도 거부한다.
     */
    private void validateImages(List<DropImageRequest> images, Long dropId) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        Set<UUID> requestedImageIds = new HashSet<>();
        images.forEach(image -> requestedImageIds.add(image.imageId()));
        Set<UUID> usedByOtherDrop = requestedImageIds.isEmpty()
                ? Set.of()
                : dropImageRepository.findUuidsUsedByOtherDrop(requestedImageIds, dropId);
        Set<UUID> seenImageIds = new HashSet<>();
        for (int index = 0; index < images.size(); index++) {
            DropImageRequest image = images.get(index);
            if (!seenImageIds.add(image.imageId())) {
                fieldErrors.add(new ErrorResponse.FieldError(
                        "images[" + index + "].imageId", "같은 요청에서 imageId가 중복됩니다."));
                continue;
            }
            if (!isExpectedImageUrl(image.imageId(), image.imageUrl())) {
                fieldErrors.add(new ErrorResponse.FieldError(
                        "images[" + index + "].imageUrl", "imageId와 일치하는 공개 이미지 URL이 아닙니다."));
            }
            if (usedByOtherDrop.contains(image.imageId())) {
                fieldErrors.add(new ErrorResponse.FieldError(
                        "images[" + index + "].imageId", "다른 DROP이 이미 사용 중인 imageId입니다."));
            }
        }
        if (!fieldErrors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, fieldErrors);
        }
    }

    private boolean isExpectedImageUrl(UUID imageId, String imageUrl) {
        for (String extension : IMAGE_EXTENSIONS) {
            if (supabaseStorageClient.publicUrl(IMAGE_OBJECT_KEY_PREFIX + imageId + "." + extension).equals(imageUrl)) {
                return true;
            }
        }
        return false;
    }
}
