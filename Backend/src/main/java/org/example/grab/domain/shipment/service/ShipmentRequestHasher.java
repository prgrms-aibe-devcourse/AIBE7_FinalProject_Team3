package org.example.grab.domain.shipment.service;

import org.example.grab.domain.shipment.dto.ShipmentRegisterRequest;
import org.example.grab.global.idempotency.RequestHash;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class ShipmentRequestHasher {

    public RequestHash hash(ShipmentRegisterRequest request) {
        String canonical = request.carrier().length() + ":" + request.carrier() + "|"
                + request.trackingNumber().length() + ":" + request.trackingNumber() + "|";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return RequestHash.from(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
