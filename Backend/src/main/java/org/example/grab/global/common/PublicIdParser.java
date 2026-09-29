package org.example.grab.global.common;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;

import java.util.UUID;

// 경로 변수의 공개 식별자(public_id)를 UUID로 바꾼다. 형식이 잘못된 값은 존재하지 않는 리소스와 같은 404로 응답한다(COMMON.md).
public final class PublicIdParser {

    private PublicIdParser() {
    }

    public static UUID parse(String publicId) {
        try {
            UUID uuid = UUID.fromString(publicId);
            // UUID.fromString은 자릿수가 모자란 값도 받아들이므로 정규 표기와 같은지 한 번 더 확인한다.
            if (uuid.toString().equalsIgnoreCase(publicId)) {
                return uuid;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 RESOURCE_NOT_FOUND로 응답한다.
        }
        throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}
