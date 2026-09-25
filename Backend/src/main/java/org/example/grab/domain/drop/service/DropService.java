package org.example.grab.domain.drop.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.dto.SellerDropListProjection;
import org.example.grab.domain.drop.dto.request.DropDraftRequest;import org.example.grab.domain.drop.dto.request.OptionGroupRequest;
import org.example.grab.domain.drop.dto.request.OptionRequest;
import org.example.grab.domain.drop.dto.request.OptionValueRequest;
import org.example.grab.domain.drop.dto.request.SelectionRequest;
import org.example.grab.domain.drop.dto.request.ShippingRequest;
import org.example.grab.domain.drop.dto.response.SellerDropDetailResponse;
import org.example.grab.domain.drop.dto.response.SellerDropListResponse;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.entity.option.DropOptionGroup;
import org.example.grab.domain.drop.entity.option.DropOptionValue;
import org.example.grab.domain.drop.entity.option.DropOptionValueMap;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.domain.category.service.CategoryService;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 판매자의 DROP 임시 저장·수정·공개를 담당한다.
 * 모든 변경은 DRAFT 상태에서만 허용하며, 공개 전 필수 항목 검증은 공개(publish) 단계의 책임이다.
 */
@Service
@RequiredArgsConstructor
public class DropService {

    private final DropRepository dropRepository;
    private final CategoryService categoryService;

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
        Drop drop = dropRepository.findById(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        drop.validateOwner(sellerId);
        applyDraft(drop, request);
        return drop;
    }

    /** DRAFT를 WISH로 공개한다. 수정과 같은 순서(조회 → 소유권)로 검증한 뒤 공개 검증은 도메인에 맡긴다. */
    @Transactional
    public Drop publish(Long sellerId, Long dropId) {
        Drop drop = dropRepository.findById(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        drop.validateOwner(sellerId);
        drop.publish(OffsetDateTime.now(ZoneOffset.UTC));
        // 공개 이후 카테고리 활성 여부를 마지막으로 확인한다. 실패하면 트랜잭션 롤백으로 WISH 전환이 취소된다.
        validateCategory(drop.getCategoryId());
        return drop;
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

    @Transactional(readOnly = true)
    public SellerDropDetailResponse findSellerDrop(Long sellerId, Long dropId) {
        Drop drop = dropRepository.findById(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        drop.validateOwner(sellerId);
        return SellerDropDetailResponse.from(drop);
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
            OptionAssembly assembly = assembleOptions(drop, request.optionGroups(), request.options());
            assembly.groups().forEach(drop::addOptionGroup);
            assembly.options().forEach(drop::addOption);
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

    /**
     * 요청의 클라이언트 키로 옵션 그룹·값·SKU를 조립한다. 키는 응답에 쓰지 않고 이 요청 안에서 참조를 연결하는 용도다.
     * 2단계로 처리한다. 1단계에서 그룹·값을 먼저 만들어 (groupKey → (valueKey → 값)) 조회 맵을 구성하고,
     * 2단계에서 각 SKU의 selections를 그 맵으로 해석해 실제 값 엔티티를 연결한다.
     * SKU는 값 ID가 아니라 클라이언트 키로 값을 참조하므로, 참조를 풀 수 있게 1단계에서 만든 키→엔티티 맵이 필요하다.
     * DRAFT 단계이므로 "모든 그룹에서 정확히 하나 선택"·"동일 조합 SKU 중복 금지"는 검증하지 않는다(공개 단계 책임).
     */
    private OptionAssembly assembleOptions(Drop drop,
                                           List<OptionGroupRequest> groupRequests,
                                           List<OptionRequest> optionRequests) {
        List<DropOptionGroup> groups = new ArrayList<>();
        // groupKey → (valueKey → 값) 조회 맵이자 그룹 key 중복 검사 인덱스(이미 담긴 key면 중복).
        Map<String, Map<String, DropOptionValue>> valuesByGroupKey = new HashMap<>();
        // 그룹 이름 중복 검사용(DB UQ(drop_id, name) 대응).
        Set<String> groupNames = new HashSet<>();

        for (int groupIndex = 0; groupIndex < groupRequests.size(); groupIndex++) {
            OptionGroupRequest groupRequest = groupRequests.get(groupIndex);
            // 그룹 key·이름 중복은 DB UQ(drop_id, name)에 걸리기 전에 도메인에서 먼저 차단한다.
            if (valuesByGroupKey.containsKey(groupRequest.key()) || !groupNames.add(groupRequest.name())) {
                throw new BusinessException(DropErrorCode.INVALID_OPTION_COMBINATION);
            }
            // sortOrder 미지정 시 요청 순서를 사용해 표시 순서를 보존한다.
            int groupSortOrder = groupRequest.sortOrder() != null ? groupRequest.sortOrder() : groupIndex;
            DropOptionGroup group = DropOptionGroup.create(drop, groupRequest.name(), groupSortOrder);

            // 값 key → 값 조회 맵과 값 내용 중복 검사용 집합(DB UQ(group_id, value) 대응).
            Map<String, DropOptionValue> valuesByKey = new HashMap<>();
            Set<String> values = new HashSet<>();
            List<OptionValueRequest> valueRequests =
                    groupRequest.values() != null ? groupRequest.values() : List.of();
            for (int valueIndex = 0; valueIndex < valueRequests.size(); valueIndex++) {
                OptionValueRequest valueRequest = valueRequests.get(valueIndex);
                // 값 key·내용 중복도 DB UQ(group_id, value) 이전에 차단한다.
                if (valuesByKey.containsKey(valueRequest.key()) || !values.add(valueRequest.value())) {
                    throw new BusinessException(DropErrorCode.INVALID_OPTION_COMBINATION);
                }
                int valueSortOrder = valueRequest.sortOrder() != null ? valueRequest.sortOrder() : valueIndex;
                DropOptionValue value = DropOptionValue.create(group, valueRequest.value(), valueSortOrder);
                group.addValue(value);
                valuesByKey.put(valueRequest.key(), value);
            }
            groups.add(group);
            valuesByGroupKey.put(groupRequest.key(), valuesByKey);
        }

        // 2단계: 각 SKU의 selections를 1단계에서 만든 값 조회 맵으로 해석해 값 엔티티를 연결한다.
        List<DropOption> options = new ArrayList<>();
        for (int optionIndex = 0; optionIndex < optionRequests.size(); optionIndex++) {
            OptionRequest optionRequest = optionRequests.get(optionIndex);
            // SKU의 가격·수량은 DB NOT NULL이므로 누락 시 500 대신 422로 응답한다.
            if (optionRequest.unitPrice() == null || optionRequest.totalQuantity() == null) {
                throw new BusinessException(DropErrorCode.INVALID_OPTION_COMBINATION);
            }
            int optionSortOrder = optionRequest.sortOrder() != null ? optionRequest.sortOrder() : optionIndex;
            DropOption option = DropOption.create(
                    drop, optionRequest.unitPrice(), optionRequest.totalQuantity(), optionSortOrder);
            if (Boolean.FALSE.equals(optionRequest.active())) {
                option.updateActive(false);
            }

            // 한 SKU가 같은 그룹을 두 번 선택하는지 검사(PK(option_id, group_id)는 그룹당 값 하나만 허용).
            Set<String> selectedGroupKeys = new HashSet<>();
            List<SelectionRequest> selections =
                    optionRequest.selections() != null ? optionRequest.selections() : List.of();
            for (SelectionRequest selection : selections) {
                Map<String, DropOptionValue> valuesByKey = valuesByGroupKey.get(selection.groupKey());
                DropOptionValue value = valuesByKey != null ? valuesByKey.get(selection.valueKey()) : null;
                // 없는 키를 참조하거나 한 SKU가 같은 그룹을 두 번 선택하면 PK(option_id, group_id) 위반이므로 사전에 거부한다.
                if (value == null || !selectedGroupKeys.add(selection.groupKey())) {
                    throw new BusinessException(DropErrorCode.INVALID_OPTION_COMBINATION);
                }
                option.addValueMap(DropOptionValueMap.create(option, value));
            }
            options.add(option);
        }

        return new OptionAssembly(groups, options);
    }

    private record OptionAssembly(List<DropOptionGroup> groups, List<DropOption> options) {
    }
}
