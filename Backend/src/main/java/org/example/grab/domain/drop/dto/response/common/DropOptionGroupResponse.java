package org.example.grab.domain.drop.dto.response.common;

import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.option.DropOptionGroup;
import org.example.grab.domain.drop.entity.option.DropOptionValue;

import java.util.List;

public record DropOptionGroupResponse(Long groupId, String name, int sortOrder, List<Value> values) {

    public record Value(Long valueId, String value, int sortOrder) {

        public static Value from(DropOptionValue value) {
            return new Value(value.getId(), value.getValue(), value.getSortOrder());
        }
    }

    public static DropOptionGroupResponse from(DropOptionGroup group) {
        return new DropOptionGroupResponse(
                group.getId(),
                group.getName(),
                group.getSortOrder(),
                group.getValues().stream().map(Value::from).toList());
    }

    public static List<DropOptionGroupResponse> listOf(Drop drop) {
        return drop.getOptionGroups().stream().map(DropOptionGroupResponse::from).toList();
    }
}
