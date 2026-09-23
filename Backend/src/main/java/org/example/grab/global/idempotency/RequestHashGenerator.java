package org.example.grab.global.idempotency;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HexFormat;

@Component
public class RequestHashGenerator {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final ObjectMapper objectMapper;

    public RequestHashGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RequestHash generate(Object request) {
        if (request == null) {
            throw new IllegalArgumentException("해시를 생성할 요청은 필수입니다.");
        }

        Object jsonValue = objectMapper.convertValue(request, Object.class);
        Object normalizedValue = normalize(jsonValue);
        byte[] normalizedJson = objectMapper.writeValueAsString(normalizedValue)
                .getBytes(StandardCharsets.UTF_8);

        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return RequestHash.from(HexFormat.of().formatHex(digest.digest(normalizedJson)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            map.forEach((key, mapValue) -> normalized.put(String.valueOf(key), normalize(mapValue)));
            return normalized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>(collection.size());
            collection.forEach(element -> normalized.add(normalize(element)));
            return normalized;
        }
        return value;
    }
}
