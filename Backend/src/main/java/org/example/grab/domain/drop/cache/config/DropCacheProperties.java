package org.example.grab.domain.drop.cache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 공개 DROP 상세·재고 응답 캐시 설정(GR-183).
 * store는 저장소 종류다. memory는 인스턴스 메모리, redis는 기존 Redis, none은 캐시를 쓰지 않는다.
 * 재고·판매·상태 변경은 커밋 후 이벤트로 바로 지우므로 TTL은 이벤트가 없는 값의 지연 상한이다.
 * 상세의 활성 WISH 수·브랜드명은 변경 이벤트가 없어 detail-ttl만큼 늦게 바뀔 수 있다.
 * 변경 이벤트는 같은 인스턴스 안에서만 전달되므로, blue/green 전환 중 다른 인스턴스에서 커밋된 변경은 TTL까지 늦게 보일 수 있다.
 * 그래서 재고 TTL도 짧게 둔다.
 * 관련 DROP 추천(GR-195)은 모든 사용자에게 같고 몇 분 늦어도 되므로 변경 이벤트 없이 related-ttl만큼 둔다.
 */
@ConfigurationProperties("grab.drop-cache")
public record DropCacheProperties(
        @DefaultValue("memory") Store store,
        @DefaultValue("5s") Duration detailTtl,
        @DefaultValue("5s") Duration stockTtl,
        @DefaultValue("3m") Duration relatedTtl
) {
    public enum Store { MEMORY, REDIS, NONE }

    public DropCacheProperties {
        if (detailTtl.isNegative() || detailTtl.isZero() || stockTtl.isNegative() || stockTtl.isZero()
                || relatedTtl.isNegative() || relatedTtl.isZero()) {
            throw new IllegalArgumentException("grab.drop-cache의 TTL은 0보다 커야 합니다.");
        }
    }
}
