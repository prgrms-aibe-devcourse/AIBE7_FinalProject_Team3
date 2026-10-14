package org.example.grab.domain.drop.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.grab.domain.drop.dto.response.common.DropCategoryResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionGroupResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionSelectionResponse;
import org.example.grab.domain.drop.dto.response.common.DropShippingResponse;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.wish.WishNotice;

import java.time.OffsetDateTime;
import java.util.List;

public record PublicDropDetailResponse(
        Long dropId,
        String name,
        String brandName,
        String description,
        List<String> imageUrls,
        List<Image> galleryImages,
        List<Image> detailImages,
        @Schema(nullable = true) Long minPrice,
        DropCategoryResponse category,
        DropStatus status,
        boolean soldOut,
        long wishCount,
        String wishNotice,
        OffsetDateTime saleStartsAt,
        OffsetDateTime saleEndsAt,
        DropShippingResponse shipping,
        List<DropOptionGroupResponse> optionGroups,
        List<Option> options,
        Actions actions,
        OffsetDateTime serverTime
) {

    public record Image(String imageUrl, String altText) {

        static List<Image> listOf(List<DropImage> images) {
            return images.stream().map(image -> new Image(image.getImageUrl(), image.getAltText())).toList();
        }
    }

    // 명세 1.3: options에는 판매자 상세와 달리 active 대신 availableStock·soldOut만 노출한다.
    public record Option(Long optionId, List<DropOptionSelectionResponse> selections,
                         Long unitPrice, int availableStock, boolean soldOut) {
    }

    // 비로그인 API이므로 사용자와 무관하게 DROP 상태·시각으로만 정한다. wishable과 wishCancelable은 규칙이 같다.
    public record Actions(boolean wishable, boolean wishCancelable, boolean orderable) {
    }

    // open-in-view=false이므로 컬렉션 접근과 DTO 변환은 서비스 트랜잭션 안에서 끝나야 한다.
    // now는 actions 판정과 serverTime에 같은 값을 쓴다.
    public static PublicDropDetailResponse of(Drop drop, String brandName, DropCategoryResponse category,
                                              long wishCount, OffsetDateTime now) {
        List<DropOption> activeOptions = drop.getOptions().stream()
                .filter(DropOption::isActive)
                .toList();
        List<DropImage> galleryImages = drop.getGalleryImages();
        return new PublicDropDetailResponse(
                drop.getId(),
                drop.getName(),
                brandName,
                drop.getDescription(),
                // imageUrls는 구버전 소비자 화면 호환용으로 GALLERY URL만 같은 순서로 둔다(명세 1.3).
                galleryImages.stream().map(DropImage::getImageUrl).toList(),
                Image.listOf(galleryImages),
                Image.listOf(drop.getDetailImages()),
                drop.getMinPrice(),
                category,
                drop.getStatus(),
                drop.isSoldOut(),
                wishCount,
                WishNotice.MESSAGE,
                drop.getSaleStartsAt(),
                drop.getSaleEndsAt(),
                DropShippingResponse.from(drop),
                DropOptionGroupResponse.listOf(drop),
                activeOptions.stream()
                        .map(option -> new Option(
                                option.getId(),
                                DropOptionSelectionResponse.listOf(option),
                                option.getUnitPrice(),
                                option.getAvailableQuantity(),
                                option.getAvailableQuantity() == 0))
                        .toList(),
                new Actions(drop.isWishable(now), drop.isWishable(now), drop.isOrderable(now)),
                now);
    }
}
