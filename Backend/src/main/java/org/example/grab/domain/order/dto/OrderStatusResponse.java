package org.example.grab.domain.order.dto;

import java.util.UUID;

public record OrderStatusResponse(UUID orderId, String status) {
}
