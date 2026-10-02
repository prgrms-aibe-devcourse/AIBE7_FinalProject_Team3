package org.example.grab.domain.dashboard.dto;

import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.payment.entity.PaymentStatus;

import java.util.LinkedHashMap;
import java.util.Map;

public record SellerDashboardSummaryResponse(
        Map<String, Long> dropCounts,
        Map<String, Long> orderCounts,
        Map<String, Long> paymentCounts,
        long reconciliationRequired,
        StockSummary stockSummary
) {

    public record StockSummary(long available, long reserved, long sold) {
    }

    /*
        건수가 없는 상태도 0으로 내려 합계가 실제 데이터와 어긋나지 않게 한다(SELLER.md 2.1).
        상태 목록을 따로 적지 않고 enum에서 가져와, 상태가 늘어도 응답이 자동으로 따라가게 한다.
     */
    public static SellerDashboardSummaryResponse of(
            Map<String, Long> dropCounts,
            Map<String, Long> orderCounts,
            Map<String, Long> paymentCounts,
            long reconciliationRequired,
            StockSummary stockSummary) {
        return new SellerDashboardSummaryResponse(
                fillZero(DropStatus.values(), dropCounts),
                fillZero(OrderStatus.values(), orderCounts),
                fillZero(PaymentStatus.values(), paymentCounts),
                reconciliationRequired,
                stockSummary);
    }

    /*
    * 상태값마다 건수가 있는 행만 DB에서 리턴하기 때문에 건수가 없는 행은 0으로 채워줌
    * Enum<?>[]로 받은 이유는 이 함수 하나로 DropStatus, OrderStatus, PaymentStatus 셋을 다 처리하기 위해서
    * */
    private static Map<String, Long> fillZero(Enum<?>[] statuses, Map<String, Long> counts) {
        // LinkedHashMap 쓴 이유 : 넣은 순서를 기억하기 위해
        // 넣은 순서를 기억하는 Map이라 응답 JSON 키가 enum 선언 순서로 고정됨. HashMap은 해시 순이라 순서가 들쭉날쭉함
        Map<String, Long> filled = new LinkedHashMap<>();
        for (Enum<?> status : statuses) {
            filled.put(status.name(), counts.getOrDefault(status.name(), 0L));
        }
        return filled;
    }
}
