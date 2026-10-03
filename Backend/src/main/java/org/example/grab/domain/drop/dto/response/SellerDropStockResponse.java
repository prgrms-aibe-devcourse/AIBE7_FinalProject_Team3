package org.example.grab.domain.drop.dto.response;

import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.entity.option.DropOptionValueMap;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public record SellerDropStockResponse(Long dropId, List<Option> options) {

    public record Option(Long optionId, String optionName, int totalStock, int availableStock,
                         int reservedStock, int soldStock) {
    }

    // open-in-view=false이므로 컬렉션 접근과 DTO 변환은 서비스 트랜잭션 안에서 끝나야 한다.
    // 판매자는 운영 현황 확인을 위해 비활성 SKU도 포함한다(정책 합의).
    public static SellerDropStockResponse from(Drop drop) {
        return new SellerDropStockResponse(
                drop.getId(),
                drop.getOptions().stream().map(SellerDropStockResponse::from).toList());
    }

    private static Option from(DropOption option) {
        return new Option(
                option.getId(),
                optionName(option),
                option.getTotalQuantity(),
                option.getAvailableQuantity(),
                option.getReservedQuantity(),
                option.getSoldQuantity());
    }

    // 값 매핑을 그룹 sortOrder 순으로 정렬한 뒤 값을 " / "로 연결한다. 매핑이 없으면 "기본"이다.
    private static String optionName(DropOption option) {
        String name = option.getValueMaps().stream()
                .sorted(Comparator.comparingInt(valueMap -> valueMap.getGroup().getSortOrder()))
                .map(DropOptionValueMap::getValue)
                .map(value -> value.getValue())
                .collect(Collectors.joining(" / "));
        return name.isEmpty() ? "기본" : name;
    }
}
