package org.example.grab.domain.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/*
    주문 생성 동시 실행 상한 설정(GR-202). 드롭 오픈 스파이크 때 주문 생성(행 잠금 + 여러 DB 왕복)이
    몰리면 CPU가 포화돼 그 드롭과 무관한 다른 요청까지 응답이 늦어진다(교차영향 측정 결과).
    상한을 걸어 두면 처리량은 CPU가 정하는 한계와 비슷하게 유지되면서, 몰린 만큼 쌓이던 스레드/CPU 점유만 줄어든다.
    GR-178(ConcurrencyLimitedPasswordEncoder)과 같은 설계다.
    - maxConcurrent: 동시에 진행하는 주문 생성 트랜잭션 수
    - acquireTimeout: 자리가 없을 때 차례를 기다리는 최대 시간
    - retryAfter: 대기 시간 안에 차례가 오지 않아 503으로 거부할 때 Retry-After 헤더에 담는 값
 */
@ConfigurationProperties(prefix = "grab.order-creation")
public record OrderCreationLimitProperties(
        int maxConcurrent,
        Duration acquireTimeout,
        Duration retryAfter
) {

    public OrderCreationLimitProperties {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("grab.order-creation.max-concurrent 값은 1 이상이어야 합니다.");
        }
        if (acquireTimeout == null || acquireTimeout.isNegative() || acquireTimeout.toNanos() <= 0) {
            throw new IllegalArgumentException("grab.order-creation.acquire-timeout 값은 0보다 커야 합니다.");
        }
        if (retryAfter == null || retryAfter.getSeconds() < 1 || retryAfter.getNano() != 0) {
            throw new IllegalArgumentException("grab.order-creation.retry-after 값은 1초 이상의 정수 초여야 합니다.");
        }
    }
}
