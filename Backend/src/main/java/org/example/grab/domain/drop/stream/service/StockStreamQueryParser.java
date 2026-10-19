package org.example.grab.domain.drop.stream.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.stream.config.StockStreamProperties;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.TreeSet;

@Component
@RequiredArgsConstructor
public class StockStreamQueryParser {

    private static final long MAX_SAFE_ID = 9_007_199_254_740_991L;
    private final StockStreamProperties properties;

    // 컨트롤러가 같은 이름의 파라미터를 합치기 전에 모든 원문 값을 전달해야 한다.
    public List<Long> parse(List<String> values) {
        if (values == null || values.size() != 1 || values.get(0) == null || values.get(0).isEmpty()) {
            throw invalidRequest();
        }
        String raw = values.get(0);
        int count = 1;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == ',' && ++count > properties.maxDropIds()) {
                throw invalidRequest();
            }
        }
        TreeSet<Long> ids = new TreeSet<>();
        for (String token : raw.split(",", -1)) {
            ids.add(parseId(token));
        }
        return List.copyOf(ids);
    }

    private long parseId(String token) {
        int firstNonZero = -1;
        for (int i = 0; i < token.length(); i++) {
            char value = token.charAt(i);
            if (value < '0' || value > '9') {
                throw invalidRequest();
            }
            if (firstNonZero == -1 && value != '0') {
                firstNonZero = i;
            }
        }
        if (firstNonZero == -1 || token.length() - firstNonZero > 16) {
            throw invalidRequest();
        }
        long id = Long.parseLong(token.substring(firstNonZero));
        if (id > MAX_SAFE_ID) {
            throw invalidRequest();
        }
        return id;
    }

    private BusinessException invalidRequest() {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST);
    }
}
