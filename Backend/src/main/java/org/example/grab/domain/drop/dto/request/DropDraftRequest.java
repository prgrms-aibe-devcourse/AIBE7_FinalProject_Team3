package org.example.grab.domain.drop.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/*
 * images는 GALLERY, detailImages는 DETAIL 이미지다(DROP.md 2.1). 용도별 개수는 수정 시 유지되는 다른 용도까지 포함한
 * 최종 상태로 검사해야 하므로 여기서 @Size로 막지 않고 서비스에서 검증한다.
 */
public record DropDraftRequest(
        @Size(max = 200) String name,
        String description,
        List<@NotNull @Valid DropImageRequest> images,
        List<@NotNull @Valid DropImageRequest> detailImages,
        @Positive Long categoryId,
        OffsetDateTime saleStartsAt,
        OffsetDateTime saleEndsAt,
        @Valid ShippingRequest shipping,
        List<@NotNull @Valid OptionGroupRequest> optionGroups,
        List<@NotNull @Valid OptionRequest> options
) {
}
