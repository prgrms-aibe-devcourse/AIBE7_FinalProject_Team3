package org.example.grab.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// GR-44 M00-04: Credential 요청을 허용하므로 와일드카드 Origin은 기동 시점에 막는다
class CorsPropertiesTest {

    @Test
    @DisplayName("와일드카드가 들어간 Origin은 거부한다")
    void rejectsWildcardOrigin() {
        assertThatThrownBy(() -> new CorsProperties(List.of("http://localhost:5173", "*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(List.of("https://*.grab.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("값이 없으면 빈 목록이 되어 다른 출처를 모두 거부한다")
    void treatsMissingOriginsAsEmpty() {
        assertThat(new CorsProperties(null).allowedOrigins()).isEmpty();
    }

    @Test
    @DisplayName("Origin 목록을 그대로 담는다")
    void keepsOrigins() {
        assertThat(new CorsProperties(List.of("http://localhost:5173", "https://app.grab.com")).allowedOrigins())
                .containsExactly("http://localhost:5173", "https://app.grab.com");
    }
}
