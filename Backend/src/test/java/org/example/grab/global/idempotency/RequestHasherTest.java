package org.example.grab.global.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {

    private final RequestHasher requestHasher = new RequestHasher();

    @Test
    @DisplayName("객체 속성 순서와 무관하게 같은 요청 해시를 생성한다")
    void createsStableHash() {
        // given
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("quantity", 2);
        first.put("optionId", 10);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("optionId", 10);
        second.put("quantity", 2);

        // when
        String firstHash = requestHasher.hash(first);
        String secondHash = requestHasher.hash(second);

        // then
        assertThat(firstHash).hasSize(64);
        assertThat(firstHash).isEqualTo(secondHash);
    }
}
