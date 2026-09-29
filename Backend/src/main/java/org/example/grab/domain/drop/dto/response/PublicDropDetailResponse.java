package org.example.grab.domain.drop.dto.response;

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
        String description,
        List<String> imageUrls,
        Long minPrice,
        PublicDropListResponse.Category category,
        DropStatus status,
        boolean soldOut,
        long wishCount,
        String wishNotice,
        OffsetDateTime saleStartsAt,
        OffsetDateTime saleEndsAt,
        SellerDropDetailResponse.Shipping shipping,
        List<SellerDropDetailResponse.OptionGroup> optionGroups,
        List<Option> options,
        Actions actions,
        OffsetDateTime serverTime
) {

    // 명세 1.3: options에는 판매자 상세와 달리 active 대신 availableStock·soldOut만 노출한다.
    public record Option(Long optionId, List<SellerDropDetailResponse.Selection> selections,
                         Long unitPrice, int availableStock, boolean soldOut) {
    }

    // 비로그인 API이므로 사용자와 무관하게 DROP 상태·시각으로만 정한다. wishable과 wishCancelable은 규칙이 같다.
    public record Actions(boolean wishable, boolean wishCancelable, boolean orderable) {
    }

    // open-in-view=false이므로 컬렉션 접근과 DTO 변환은 서비스 트랜잭션 안에서 끝나야 한다.
    // now는 actions 판정과 serverTime에 같은 값을 쓴다.
    public static PublicDropDetailResponse of(Drop drop, PublicDropListResponse.Category category,
                                              long wishCount, OffsetDateTime now) {
        List<DropOption> activeOptions = drop.getOptions().stream()
                .filter(DropOption::isActive)
                .toList();
        return new PublicDropDetailResponse(
                drop.getId(),
                drop.getName(),
                drop.getDescription(),
                drop.getImages().stream().map(DropImage::getImageUrl).toList(),
                SellerDropDetailResponse.minPrice(drop),
                category,
                drop.getStatus(),
                drop.isSoldOut(),
                wishCount,
                WishNotice.MESSAGE,
                drop.getSaleStartsAt(),
                drop.getSaleEndsAt(),
                new SellerDropDetailResponse.Shipping(drop.getShippingFee(), drop.getShippingNotice()),
                drop.getOptionGroups().stream()
                        .map(group -> new SellerDropDetailResponse.OptionGroup(
                                group.getId(),
                                group.getName(),
                                group.getSortOrder(),
                                group.getValues().stream()
                                        .map(value -> new SellerDropDetailResponse.OptionValue(
                                                value.getId(), value.getValue(), value.getSortOrder()))
                                        .toList()))
                        .toList(),
                activeOptions.stream()
                        .map(option -> new Option(
                                option.getId(),
                                SellerDropDetailResponse.selections(option),
                                option.getUnitPrice(),
                                option.getAvailableQuantity(),
                                option.getAvailableQuantity() == 0))
                        .toList(),
                new Actions(drop.isWishable(now), drop.isWishable(now), drop.isOrderable(now)),
                now);
    }
}
