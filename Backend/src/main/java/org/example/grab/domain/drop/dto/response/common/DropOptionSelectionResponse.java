package org.example.grab.domain.drop.dto.response.common;

import org.example.grab.domain.drop.entity.option.DropOption;

import java.util.Comparator;
import java.util.List;

public record DropOptionSelectionResponse(Long groupId, Long valueId) {

    // 값 매핑은 그룹 정렬 순서를 따른다.
    public static List<DropOptionSelectionResponse> listOf(DropOption option) {
        return option.getValueMaps().stream()
                .sorted(Comparator.comparingInt(valueMap -> valueMap.getGroup().getSortOrder()))
                .map(valueMap -> new DropOptionSelectionResponse(
                        valueMap.getGroup().getId(), valueMap.getValue().getId()))
                .toList();
    }
}
