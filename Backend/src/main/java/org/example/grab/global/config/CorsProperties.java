package org.example.grab.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/*
    CORS 허용 Origin(COMMON 1.3, GR-44 M00-04). 기본값은 로컬 Vite 개발 서버이고 CORS_ALLOWED_ORIGINS로 덮어쓴다.
    운영을 Nginx 뒤 같은 출처로 서비스하면 CORS가 쓰이지 않는다. 빈 목록은 다른 출처를 모두 거부한다는 뜻이다.
 */
@ConfigurationProperties(prefix = "grab.cors")
public record CorsProperties(
        // scheme·host·port가 정확히 같아야 하고 끝에 /를 붙이지 않는다. 여러 개는 쉼표로 구분한다
        List<String> allowedOrigins
) {

    /*
        Credential 요청을 허용하므로 와일드카드 Origin은 쓸 수 없다. Spring은 요청을 처리할 때에야 예외를 던지므로 기동 시점에 막는다.
     */
    public CorsProperties(List<String> allowedOrigins) {
        List<String> origins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalArgumentException("grab.cors.allowed-origins에는 와일드카드(*)를 쓸 수 없습니다.");
        }
        this.allowedOrigins = origins;
    }
}
