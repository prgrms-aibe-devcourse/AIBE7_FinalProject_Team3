package org.example.grab.domain.drop.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.dto.PublicDropListProjection;
import org.example.grab.domain.drop.dto.PublicDropSort;
import org.example.grab.domain.drop.dto.SellerDropListProjection;
import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.request.ShippingRequest;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.dto.response.common.DropCategoryResponse;
import org.example.grab.domain.drop.dto.response.SellerDropDetailResponse;
import org.example.grab.domain.drop.dto.response.SellerDropListResponse;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.wish.service.WishQueryService;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 판매자의 DROP 임시 저장·수정·공개·조회를 담당한다.
 * 모든 변경은 DRAFT 상태에서만 허용하며, 공개 전 필수 항목 검증은 공개(publish) 단계의 책임이다.
 */
@Service
@RequiredArgsConstructor
public class DropService {

    private static final List<String> PUBLIC_STATUSES = List.of(
            DropStatus.WISH.name(), DropStatus.GRAB.name(), DropStatus.ENDED.name());
    private static final int KEYWORD_MAX_LENGTH = 100;

    private final DropRepository dropRepository;
    private final CategoryService categoryService;
    private final WishQueryService wishQueryService;

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
     */
    @Transactional
    public Drop updateDraft(Long sellerId, Long dropId, DropDraftRequest request) {
        Drop drop = findOwnedDrop(sellerId, dropId);
        applyDraft(drop, request);
        return drop;
    }

    /** DRAFT를 WISH로 공개한다. 수정과 같은 순서(조회 → 소유권)로 검증한 뒤 공개 검증은 도메인에 맡긴다. */
    @Transactional
    public Drop publish(Long sellerId, Long dropId) {
        Drop drop = findOwnedDrop(sellerId, dropId);
        drop.publish(OffsetDateTime.now(ZoneOffset.UTC));
        // 공개 이후 카테고리 활성 여부를 마지막으로 확인한다. 실패하면 트랜잭션 롤백으로 WISH 전환이 취소된다.
        validateCategory(drop.getCategoryId());
        return drop;
    }

    /**
     * WISH 등록·취소 가능 여부를 판정한다. 등록과 취소의 규칙이 같아 하나의 메서드로 공유한다.
     * 존재하지 않거나 DRAFT인 DROP은 DROP_NOT_FOUND로 숨기고, 그 밖의 불가 상태는 도메인이 판정한다.
     */
    @Transactional(readOnly = true)
    public void validateWishable(Long dropId, OffsetDateTime now) {
        Drop drop = dropRepository.findById(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        drop.validateWishable(now);
    }

    // 목록은 조회 전용 트랜잭션에서 쿼리 한 번으로 가져오고, 프로젝션을 응답 DTO로 변환한다.
    @Transactional(readOnly = true)
    public PageResponse<SellerDropListResponse> findSellerDrops(
            Long sellerId, DropStatus status, int page, int size) {
        Page<SellerDropListProjection> drops = dropRepository.findSellerDrops(
                sellerId, status, PageRequest.of(page, size));
        List<SellerDropListResponse> content = drops.getContent().stream()
                .map(projection -> new SellerDropListResponse(
                        projection.getDropId(),
                        projection.getName(),
                        projection.getStatus(),
                        projection.getMinPrice(),
                        projection.getCreatedAt()))
                .toList();
        return new PageResponse<>(content, page, size, drops.getTotalElements(),
                drops.getTotalPages(), drops.hasNext());
    }

    /*
     * 공개 DROP 목록. DRAFT·CANCELED는 어떤 경우에도 제외한다(DISC-001).
     * status가 없으면 WISH·GRAB·ENDED를 모두, 있으면 그 상태만 조회한다.
     * 정렬은 허용 속성을 컬럼명으로 매핑한 Sort를 쓰고, keyword는 trim·이스케이프해 넘긴다.
     */
    @Transactional(readOnly = true)
    public PageResponse<PublicDropListResponse> findPublicDrops(
            DropStatus status, Long categoryId, String keyword, Boolean soldOut,
            PublicDropSort sort, int page, int size) {
        List<String> statuses = resolvePublicStatuses(status);
        PublicDropSort effectiveSort = sort != null ? sort : PublicDropSort.parse(null);
        Page<PublicDropListProjection> drops = dropRepository.findPublicDrops(
                statuses, categoryId, normalizeKeyword(keyword), soldOut,
                PageRequest.of(page, size, effectiveSort.toSort()));
        List<PublicDropListResponse> content = drops.getContent().stream()
                .map(PublicDropListResponse::from)
                .toList();
        return new PageResponse<>(content, page, size, drops.getTotalElements(),
                drops.getTotalPages(), drops.hasNext());
    }

    @Transactional(readOnly = true)
    public SellerDropDetailResponse findSellerDrop(Long sellerId, Long dropId) {
        return SellerDropDetailResponse.from(findOwnedDrop(sellerId, dropId));
    }

    /**
     * 공개 DROP 상세. 비로그인 조회이며 DRAFT는 존재를 숨겨 DROP_NOT_FOUND로 응답한다.
     * CANCELED는 0절 확정대로 200으로 보여 주고 actions가 모두 false가 된다.
     * now는 actions 판정과 응답 serverTime에 같은 값을 쓴다.
     */
    @Transactional(readOnly = true)
    public PublicDropDetailResponse findPublicDrop(Long dropId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Drop drop = dropRepository.findById(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        if (drop.getStatus() == DropStatus.DRAFT) {
            throw new BusinessException(DropErrorCode.DROP_NOT_FOUND);
        }
        long wishCount = wishQueryService.countActiveByDropId(dropId);
        return PublicDropDetailResponse.of(drop, toCategory(drop.getCategoryId()), wishCount, now);
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
        Drop drop = dropRepository.findById(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        drop.validateOwner(sellerId);
        return drop;
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
        boolean replaceImages = request.imageUrls() != null;
        boolean hasOptionGroups = request.optionGroups() != null;
        boolean hasOptions = request.options() != null;
        if (hasOptionGroups != hasOptions) {
            throw new BusinessException(DropErrorCode.INVALID_OPTION_COMBINATION);
        }
        boolean replaceOptions = hasOptionGroups;
        if (!replaceImages && !replaceOptions) {
            return;
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
            toImages(drop, request.imageUrls()).forEach(drop::addImage);
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
    private List<DropImage> toImages(Drop drop, List<String> imageUrls) {
        String altText = drop.getName() != null ? drop.getName() : "";
        List<DropImage> images = new ArrayList<>();
        for (int index = 0; index < imageUrls.size(); index++) {
            images.add(DropImage.create(drop, imageUrls.get(index), index, altText));
        }
        return images;
    }
}
