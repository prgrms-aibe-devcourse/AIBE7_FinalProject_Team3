package org.example.grab.domain.drop.dto.response;

import org.example.grab.domain.drop.dto.response.common.DropOptionGroupResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionSelectionResponse;
import org.example.grab.domain.drop.dto.response.common.DropShippingResponse;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.entity.option.DropOption;

import java.time.OffsetDateTime;
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
        DropShippingResponse shipping,
        List<DropOptionGroupResponse> optionGroups,
        List<Option> options
) {

    public record Option(Long optionId, List<DropOptionSelectionResponse> selections, Long unitPrice,
                         int totalQuantity, int reservedQuantity, int soldQuantity, boolean active, int sortOrder) {
    }

    // open-in-view=false이므로 컬렉션 접근과 DTO 변환은 서비스 트랜잭션 안에서 끝나야 한다.
    public static SellerDropDetailResponse from(Drop drop) {
        return new SellerDropDetailResponse(
                drop.getId(),
                drop.getName(),
                drop.getDescription(),
                drop.getImages().stream().map(DropImage::getImageUrl).toList(),
                drop.getMinPrice(),
                drop.getCategoryId(),
                drop.getStatus(),
                drop.getSaleStartsAt(),
                drop.getSaleEndsAt(),
                DropShippingResponse.from(drop),
                DropOptionGroupResponse.listOf(drop),
                drop.getOptions().stream()
                        .map(option -> new Option(
                                option.getId(),
                                DropOptionSelectionResponse.listOf(option),
                                option.getUnitPrice(),
                                option.getTotalQuantity(),
                                option.getReservedQuantity(),
                                option.getSoldQuantity(),
                                option.isActive(),
                                option.getSortOrder()))
                        .toList());
    }
}
