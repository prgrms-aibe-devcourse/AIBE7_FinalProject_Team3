package org.example.grab.global.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHashGeneratorTest {

    private final RequestHashGenerator generator = new RequestHashGenerator(JsonMapper.builder().build());

    @Test
    @DisplayName("객체와 맵 필드 순서가 달라도 같은 요청은 같은 해시를 생성한다")
    void generatesSameHashForSameRequest() {
        // given
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("dropId", 10);
        first.put("items", List.of(Map.of("optionId", 100, "quantity", 2)));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("items", List.of(Map.of("quantity", 2, "optionId", 100)));
        second.put("dropId", 10);

        // when
        RequestHash firstHash = generator.generate(first);
        RequestHash secondHash = generator.generate(second);

        // then
        assertThat(firstHash).isEqualTo(secondHash);
        assertThat(firstHash.value()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("요청 값이 다르면 다른 해시를 생성한다")
    void generatesDifferentHashForDifferentRequest() {
        // given
        Map<String, Object> first = Map.of("optionId", 100, "quantity", 1);
        Map<String, Object> second = Map.of("optionId", 100, "quantity", 2);

        // when
        RequestHash firstHash = generator.generate(first);
        RequestHash secondHash = generator.generate(second);

        // then
        assertThat(firstHash).isNotEqualTo(secondHash);
    }
}
