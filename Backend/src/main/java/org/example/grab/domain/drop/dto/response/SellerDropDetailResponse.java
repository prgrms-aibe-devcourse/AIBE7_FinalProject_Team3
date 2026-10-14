package org.example.grab.domain.drop.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.grab.domain.drop.dto.response.common.DropOptionGroupResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionSelectionResponse;
import org.example.grab.domain.drop.dto.response.common.DropShippingResponse;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.entity.option.DropOption;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SellerDropDetailResponse(
        Long dropId,
        String name,
        String description,
        List<Image> images,
        List<Image> detailImages,
        Long minPrice,
        Long categoryId,
        DropStatus status,
        OffsetDateTime saleStartsAt,
        OffsetDateTime saleEndsAt,
        DropShippingResponse shipping,
        List<DropOptionGroupResponse> optionGroups,
        List<Option> options
) {

    // 수정 화면이 받은 값을 그대로 다시 보낼 수 있도록 요청 원소와 같은 형태로 둔다(명세 2.3).
    public record Image(UUID imageId, String imageUrl, String altText) {

        static List<Image> listOf(List<DropImage> images) {
            return images.stream()
                    .map(image -> new Image(image.getUuid(), image.getImageUrl(), image.getAltText()))
                    .toList();
        }
    }

    // 중첩 이름 Option이 다른 응답 DTO와 충돌하지 않도록 고유 스키마 이름을 지정한다(GR-98).
    @Schema(name = "SellerDropDetailOption")
    public record Option(Long optionId, List<DropOptionSelectionResponse> selections, Long unitPrice,
                         int totalQuantity, int reservedQuantity, int soldQuantity, boolean active, int sortOrder) {
    }

    // open-in-view=false이므로 컬렉션 접근과 DTO 변환은 서비스 트랜잭션 안에서 끝나야 한다.
    public static SellerDropDetailResponse from(Drop drop) {
        return new SellerDropDetailResponse(
                drop.getId(),
                drop.getName(),
                drop.getDescription(),
                Image.listOf(drop.getGalleryImages()),
                Image.listOf(drop.getDetailImages()),
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
