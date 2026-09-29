package org.example.grab.domain.order.controller;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;

import java.util.UUID;

// 경로의 주문 ID를 UUID로 바꾼다. 형식이 잘못된 ID는 존재하지 않는 주문과 같은 404로 응답한다.
final class OrderIdParser {

    private OrderIdParser() {
    }

    static UUID parse(String orderId) {
        try {
            UUID uuid = UUID.fromString(orderId);
            // UUID.fromString은 자릿수가 모자란 값도 받아들이므로 정규 표기와 같은지 한 번 더 확인한다.
            if (uuid.toString().equalsIgnoreCase(orderId)) {
                return uuid;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 RESOURCE_NOT_FOUND로 응답한다.
        }
        throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}
