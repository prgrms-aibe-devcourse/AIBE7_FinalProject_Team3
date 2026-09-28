package org.example.grab.domain.drop.dto.response;

import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.entity.option.DropOption;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

public record SellerDropDetailResponse(
        Long dropId,
        String name,
        String description,
        List<String> imageUrls,
        Long minPrice,
        Long categoryId,
        DropStatus status,
        OffsetDateTime saleStartsAt,
        OffsetDateTime saleEndsAt,
        Shipping shipping,
        List<OptionGroup> optionGroups,
        List<Option> options
) {

    public record Shipping(Long shippingFee, String shippingNotice) {
    }

    public record OptionGroup(Long groupId, String name, int sortOrder, List<OptionValue> values) {
    }

    public record OptionValue(Long valueId, String value, int sortOrder) {
    }

    public record Option(Long optionId, List<Selection> selections, Long unitPrice, int totalQuantity,
                         int reservedQuantity, int soldQuantity, boolean active, int sortOrder) {
    }

    public record Selection(Long groupId, Long valueId) {
    }

    // open-in-view=false이므로 컬렉션 접근과 DTO 변환은 서비스 트랜잭션 안에서 끝나야 한다.
    public static SellerDropDetailResponse from(Drop drop) {
        return new SellerDropDetailResponse(
                drop.getId(),
                drop.getName(),
                drop.getDescription(),
                drop.getImages().stream().map(DropImage::getImageUrl).toList(),
                minPrice(drop),
                drop.getCategoryId(),
                drop.getStatus(),
                drop.getSaleStartsAt(),
                drop.getSaleEndsAt(),
                new Shipping(drop.getShippingFee(), drop.getShippingNotice()),
                drop.getOptionGroups().stream()
                        .map(group -> new OptionGroup(
                                group.getId(),
                                group.getName(),
                                group.getSortOrder(),
                                group.getValues().stream()
                                        .map(value -> new OptionValue(
                                                value.getId(), value.getValue(), value.getSortOrder()))
                                        .toList()))
                        .toList(),
                drop.getOptions().stream()
                        .map(option -> new Option(
                                option.getId(),
                                selections(option),
                                option.getUnitPrice(),
                                option.getTotalQuantity(),
                                option.getReservedQuantity(),
                                option.getSoldQuantity(),
                                option.isActive(),
                                option.getSortOrder()))
                        .toList());
    }

    // 목록과 같은 규칙: 활성 SKU 중 최저가, 활성 SKU가 없으면 null.
    private static Long minPrice(Drop drop) {
        return drop.getOptions().stream()
                .filter(DropOption::isActive)
                .map(DropOption::getUnitPrice)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    // 값 매핑은 그룹 정렬 순서를 따른다.
    private static List<Selection> selections(DropOption option) {
        return option.getValueMaps().stream()
                .sorted(Comparator.comparingInt(valueMap -> valueMap.getGroup().getSortOrder()))
                .map(valueMap -> new Selection(valueMap.getGroup().getId(), valueMap.getValue().getId()))
                .toList();
    }
}
