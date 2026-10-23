package org.example.grab.domain.drop.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.option.DropOption;

import java.time.OffsetDateTime;
import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record PublicDropStockResponse(Long dropId, List<Option> options, OffsetDateTime serverTime) {

    // 명세 1.2: 전체·확보·판매 수량과 옵션명은 공개 응답에 노출하지 않는다.
    // 중첩 이름 Option이 다른 응답 DTO와 충돌하지 않도록 고유 스키마 이름을 지정한다(GR-98).
    // 세 필드는 항상 채워지므로 필수로 표시해 프론트 생성 타입이 선택 필드가 되지 않게 한다(GR-148).
    @Schema(name = "PublicDropStockOption")
    public record Option(
            @Schema(requiredMode = REQUIRED) Long optionId,
            @Schema(requiredMode = REQUIRED) int availableStock,
            @Schema(requiredMode = REQUIRED) boolean soldOut) {

        private static Option from(DropOption option) {
            int availableStock = option.getAvailableQuantity();
            return new Option(option.getId(), availableStock, availableStock == 0);
        }
    }

    // open-in-view=false이므로 컬렉션 접근과 DTO 변환은 서비스 트랜잭션 안에서 끝나야 한다.
    // 공개 상세와 동일하게 활성 SKU만 반환하고, DROP.options의 @OrderBy(sortOrder ASC) 순서를 따른다.
    public static PublicDropStockResponse of(Drop drop, OffsetDateTime now) {
        List<Option> options = drop.getOptions().stream()
                .filter(DropOption::isActive)
                .map(Option::from)
                .toList();
        return new PublicDropStockResponse(drop.getId(), options, now);
    }
}
