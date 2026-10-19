package org.example.grab.domain.order.dto;

import java.time.Instant;
import java.util.UUID;

public interface SellerOrderListProjection {

    UUID getOrderId();

    String getOrderNumber();

    long getDropId();

    String getProductName();

    String getSellerName();

    String getOrderStatus();

    String getPaymentStatus();

    long getItemsAmount();

    long getShippingAmount();

    long getTotalAmount();

    // 네이티브 쿼리 프로젝션은 timestamptz를 OffsetDateTime이 아닌 Instant로 반환한다
    // (JPQL 엔티티 매핑과 달리 드라이버가 직접 돌려준 타입이라 변환기가 없으면 실패한다).
    Instant getOrderedAt();
}
