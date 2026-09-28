package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.dto.request.OptionGroupRequest;
import org.example.grab.domain.drop.dto.request.OptionRequest;
import org.example.grab.domain.drop.dto.request.OptionValueRequest;
import org.example.grab.domain.drop.dto.request.SelectionRequest;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.entity.option.DropOptionGroup;
import org.example.grab.domain.drop.entity.option.DropOptionValue;
import org.example.grab.domain.drop.entity.option.DropOptionValueMap;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.error.BusinessException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DropOptionAssembler {

    private DropOptionAssembler() {
    }

    /**
     * SKU는 아직 DB ID가 없는 그룹·값을 요청 키로 참조한다.
     * 그룹·값을 먼저 만들고 키로 찾아 SKU에 연결한다. 조합의 완전성 검사는 공개 단계에서 수행한다.
     */
    static void apply(Drop drop, List<OptionGroupRequest> groupRequests, List<OptionRequest> optionRequests) {
        List<DropOptionGroup> groups = new ArrayList<>();
        Map<String, Map<String, DropOptionValue>> valuesByGroupKey = new HashMap<>();
        Set<String> groupNames = new HashSet<>();

        for (int groupIndex = 0; groupIndex < groupRequests.size(); groupIndex++) {
            OptionGroupRequest groupRequest = groupRequests.get(groupIndex);
            // 중복 키·이름은 DB 고유 제약에 맡기지 않고 같은 비즈니스 오류로 반환한다.
            if (valuesByGroupKey.containsKey(groupRequest.key()) || !groupNames.add(groupRequest.name())) {
                throw new BusinessException(DropErrorCode.INVALID_OPTION_COMBINATION);
            }
            // 정렬 순서가 없으면 요청 배열 순서를 그대로 사용한다.
            int groupSortOrder = groupRequest.sortOrder() != null ? groupRequest.sortOrder() : groupIndex;
            DropOptionGroup group = DropOptionGroup.create(drop, groupRequest.name(), groupSortOrder);

            Map<String, DropOptionValue> valuesByKey = new HashMap<>();
            Set<String> values = new HashSet<>();
            List<OptionValueRequest> valueRequests =
                    groupRequest.values() != null ? groupRequest.values() : List.of();
            for (int valueIndex = 0; valueIndex < valueRequests.size(); valueIndex++) {
                OptionValueRequest valueRequest = valueRequests.get(valueIndex);
                // 값의 중복도 그룹 단위로 검사해 DB 제약 오류 대신 같은 비즈니스 오류를 반환한다.
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

        List<DropOption> options = new ArrayList<>();
        for (int optionIndex = 0; optionIndex < optionRequests.size(); optionIndex++) {
            OptionRequest optionRequest = optionRequests.get(optionIndex);
            // 누락된 가격·수량은 DB 제약 위반 전에 비즈니스 오류로 반환한다.
            if (optionRequest.unitPrice() == null || optionRequest.totalQuantity() == null) {
                throw new BusinessException(DropErrorCode.INVALID_OPTION_COMBINATION);
            }
            int optionSortOrder = optionRequest.sortOrder() != null ? optionRequest.sortOrder() : optionIndex;
            DropOption option = DropOption.create(
                    drop, optionRequest.unitPrice(), optionRequest.totalQuantity(), optionSortOrder);
            if (Boolean.FALSE.equals(optionRequest.active())) {
                option.updateActive(false);
            }

            Set<String> selectedGroupKeys = new HashSet<>();
            List<SelectionRequest> selections =
                    optionRequest.selections() != null ? optionRequest.selections() : List.of();
            for (SelectionRequest selection : selections) {
                Map<String, DropOptionValue> valuesByKey = valuesByGroupKey.get(selection.groupKey());
                DropOptionValue value = valuesByKey != null ? valuesByKey.get(selection.valueKey()) : null;
                // 없는 키나 같은 그룹의 중복 선택은 옵션-그룹 매핑의 PK 제약에 걸리기 전에 거부한다.
                if (value == null || !selectedGroupKeys.add(selection.groupKey())) {
                    throw new BusinessException(DropErrorCode.INVALID_OPTION_COMBINATION);
                }
                option.addValueMap(DropOptionValueMap.create(option, value));
            }
            options.add(option);
        }

        // 조립 중 오류가 나면 DROP에 일부만 연결된 상태가 남지 않도록 마지막에 붙인다.
        groups.forEach(drop::addOptionGroup);
        options.forEach(drop::addOption);
    }
}
