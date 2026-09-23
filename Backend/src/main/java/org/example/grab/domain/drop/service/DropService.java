package org.example.grab.domain.drop.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.request.OptionGroupRequest;
import org.example.grab.domain.drop.dto.request.OptionRequest;
import org.example.grab.domain.drop.dto.request.OptionValueRequest;
import org.example.grab.domain.drop.dto.request.SelectionRequest;
import org.example.grab.domain.drop.dto.request.ShippingRequest;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.entity.option.DropOptionGroup;
import org.example.grab.domain.drop.entity.option.DropOptionValue;
import org.example.grab.domain.drop.entity.option.DropOptionValueMap;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.global.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DropService {

    private final DropRepository dropRepository;

    @Transactional
    public Drop createDraft(Long sellerId, DropDraftRequest request) {
        Drop drop = Drop.createDraft(sellerId);
        applyDraft(drop, request);
        return dropRepository.save(drop);
    }

    @Transactional
    public Drop updateDraft(Long sellerId, Long dropId, DropDraftRequest request) {
        Drop drop = dropRepository.findById(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        drop.validateOwner(sellerId);
        applyDraft(drop, request);
        return drop;
    }

    private void applyDraft(Drop drop, DropDraftRequest request) {
        ShippingRequest shipping = request.shipping();
        drop.updateDraft(
                request.name(),
                request.description(),
                request.categoryId(),
                shipping != null ? shipping.shippingFee() : null,
                shipping != null ? shipping.shippingNotice() : null,
                request.saleStartsAt(),
                request.saleEndsAt());

        if (request.imageUrls() != null) {
            drop.replaceImages(toImages(drop, request.imageUrls()));
        }
        if (request.optionGroups() != null || request.options() != null) {
            OptionAssembly assembly = assembleOptions(
                    drop,
                    request.optionGroups() != null ? request.optionGroups() : List.of(),
                    request.options() != null ? request.options() : List.of());
            drop.replaceOptionGroups(assembly.groups());
            drop.replaceOptions(assembly.options());
        }
    }

    private List<DropImage> toImages(Drop drop, List<String> imageUrls) {
        String altText = drop.getName() != null ? drop.getName() : "";
        List<DropImage> images = new ArrayList<>();
        for (int index = 0; index < imageUrls.size(); index++) {
            images.add(DropImage.create(drop, imageUrls.get(index), index, altText));
        }
        return images;
    }

    private OptionAssembly assembleOptions(Drop drop,
                                           List<OptionGroupRequest> groupRequests,
                                           List<OptionRequest> optionRequests) {
        List<DropOptionGroup> groups = new ArrayList<>();
        Map<String, Map<String, DropOptionValue>> valuesByGroupKey = new HashMap<>();
        Set<String> groupNames = new HashSet<>();

        for (int groupIndex = 0; groupIndex < groupRequests.size(); groupIndex++) {
            OptionGroupRequest groupRequest = groupRequests.get(groupIndex);
            if (valuesByGroupKey.containsKey(groupRequest.key()) || !groupNames.add(groupRequest.name())) {
                throw new BusinessException(DropErrorCode.INVALID_OPTION_COMBINATION);
            }
            int groupSortOrder = groupRequest.sortOrder() != null ? groupRequest.sortOrder() : groupIndex;
            DropOptionGroup group = DropOptionGroup.create(drop, groupRequest.name(), groupSortOrder);

            Map<String, DropOptionValue> valuesByKey = new HashMap<>();
            Set<String> values = new HashSet<>();
            List<OptionValueRequest> valueRequests =
                    groupRequest.values() != null ? groupRequest.values() : List.of();
            for (int valueIndex = 0; valueIndex < valueRequests.size(); valueIndex++) {
                OptionValueRequest valueRequest = valueRequests.get(valueIndex);
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
