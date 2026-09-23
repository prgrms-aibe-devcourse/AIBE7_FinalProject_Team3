package org.example.grab.domain.order.dto;

import java.time.OffsetDateTime;
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

    OffsetDateTime getOrderedAt();
}
