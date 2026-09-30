package org.example.grab.domain.drop.validation;

import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.entity.option.DropOptionGroup;
import org.example.grab.domain.drop.entity.option.DropOptionValue;
import org.example.grab.domain.drop.entity.option.DropOptionValueMap;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.ErrorCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DropPublishValidator {

    private DropPublishValidator() {
    }

    // 필드명은 요청 DTO(DropDraftRequest) 기준으로 적어 클라이언트가 입력 위치를 바로 찾을 수 있게 한다.
    public static void validateRequired(Drop drop) {
        List<ErrorResponse.FieldError> missing = new ArrayList<>();
        if (drop.getName() == null || drop.getName().isBlank()) {
            missing.add(required("name"));
        }
        if (drop.getDescription() == null || drop.getDescription().isBlank()) {
            missing.add(required("description"));
        }
        if (drop.getCategoryId() == null) {
            missing.add(required("categoryId"));
        }
        if (drop.getShippingFee() == null) {
            missing.add(required("shipping.shippingFee"));
        }
        if (drop.getShippingNotice() == null || drop.getShippingNotice().isBlank()) {
            missing.add(required("shipping.shippingNotice"));
        }
        if (drop.getSaleStartsAt() == null) {
            missing.add(required("saleStartsAt"));
        }
        if (drop.getSaleEndsAt() == null) {
            missing.add(required("saleEndsAt"));
        }
        if (drop.getImages().isEmpty()) {
            missing.add(new ErrorResponse.FieldError("imageUrls", "이미지를 1개 이상 등록해야 합니다."));
        }
        if (drop.getOptions().isEmpty()) {
            missing.add(new ErrorResponse.FieldError("options", "구매 옵션을 1개 이상 등록해야 합니다."));
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, missing);
        }
    }

    /**
     * SKU 조합은 엔티티 인스턴스가 아니라 (그룹명 → 값) 맵으로 비교한다.
     * LAZY 프록시와 실제 엔티티가 섞이면 인스턴스 비교가 틀어지고, 그룹명·값은 앞 단계에서 고유함을 확인하므로 키로 충분하다.
     * 옵션 없는 상품은 그룹 0개 + 선택 없는 SKU 1개이며, 같은 규칙으로 빈 조합 하나만 허용된다.
     */
    public static void validateOptions(Drop drop) {
        Set<String> groupNames = new HashSet<>();
        for (int i = 0; i < drop.getOptionGroups().size(); i++) {
            DropOptionGroup group = drop.getOptionGroups().get(i);
            Set<String> values = new HashSet<>();
            boolean duplicateValue = group.getValues().stream()
                    .map(DropOptionValue::getValue)
                    .anyMatch(value -> !values.add(value));
            if (!groupNames.add(group.getName()) || duplicateValue) {
                throw optionError(DropErrorCode.INVALID_OPTION_COMBINATION,
                        "optionGroups[" + i + "]", "옵션 그룹명 또는 그룹 안의 옵션값이 중복됩니다.");
            }
        }

        Set<Map<String, String>> combinations = new HashSet<>();
        boolean hasSellableOption = false;
        for (int i = 0; i < drop.getOptions().size(); i++) {
            DropOption option = drop.getOptions().get(i);
            String field = "options[" + i + "]";

            Map<String, String> combination = new HashMap<>();
            boolean selectedGroupTwice = false;
            for (DropOptionValueMap valueMap : option.getValueMaps()) {
                selectedGroupTwice |= combination.put(valueMap.getGroup().getName(), valueMap.getValue().getValue()) != null;
            }
            if (selectedGroupTwice || !combination.keySet().equals(groupNames)) {
                throw optionError(DropErrorCode.INVALID_OPTION_COMBINATION,
                        field, "모든 옵션 그룹에서 값을 정확히 하나씩 선택해야 합니다.");
            }
            if (!combinations.add(combination)) {
                throw optionError(DropErrorCode.DUPLICATE_OPTION_COMBINATION,
                        field, "동일한 옵션값 조합의 SKU가 이미 있습니다.");
            }
            if (option.getUnitPrice() == null || option.getUnitPrice() < 0 || option.getTotalQuantity() < 0) {
                throw optionError(DropErrorCode.INVALID_OPTION_COMBINATION,
                        field, "가격과 재고는 0 이상이어야 합니다.");
            }
            hasSellableOption |= option.isActive() && option.getAvailableQuantity() >= 1;
        }
        if (!hasSellableOption) {
            throw optionError(DropErrorCode.INVALID_OPTION_COMBINATION,
                    "options", "활성 상태이며 재고가 1개 이상인 SKU가 최소 하나 필요합니다.");
        }
    }

    private static ErrorResponse.FieldError required(String field) {
        return new ErrorResponse.FieldError(field, "공개하려면 필수로 입력해야 합니다.");
    }

    private static BusinessException optionError(ErrorCode errorCode, String field, String reason) {
        return new BusinessException(errorCode, List.of(new ErrorResponse.FieldError(field, reason)));
    }
}
