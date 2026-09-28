package org.example.grab.domain.order.service;

import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.global.idempotency.RequestHash;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class OrderRequestHasher {

    public RequestHash hash(OrderCreateRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.dropId());
        request.items().stream()
                .sorted((left, right) -> left.optionId().compareTo(right.optionId()))
                .forEach(item -> {
                    append(canonical, item.optionId());
                    append(canonical, item.quantity());
                });
        OrderCreateRequest.ShippingAddress address = request.shippingAddress();
        append(canonical, address.recipient());
        append(canonical, address.phone());
        append(canonical, address.postalCode());
        append(canonical, address.address1());
        append(canonical, address.address2());
        append(canonical, address.deliveryMemo());

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return RequestHash.from(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private void append(StringBuilder target, Object value) {
        String text = value == null ? "" : value.toString();
        target.append(text.length()).append(':').append(text).append('|');
    }
}
