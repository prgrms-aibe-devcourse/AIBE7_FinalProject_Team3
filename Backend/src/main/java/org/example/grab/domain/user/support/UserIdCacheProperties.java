package org.example.grab.domain.user.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/*
    인증 요청의 회원 ID 변환(public_id → users.id) 캐시 설정(GR-211 M00-02).
    대응이 바뀌지 않아 만료 없이 최대 항목 수로만 메모리를 제한한다(M00-03). 항목 하나가 약 100바이트라 기본 10만 개는 약 10MB다.
    한도를 넘으면 덜 쓰는 항목부터 버리고, 버린 회원은 다음 요청에 DB에서 다시 채운다.
    값이 잘못되면 생성자에서 예외를 던져 애플리케이션이 기동하지 않게 한다.
    명시적 정규 생성자에는 레코드 구성 요소의 어노테이션이 전달되지 않으므로 @DefaultValue는 생성자 매개변수에 둔다.
 */
@ConfigurationProperties("grab.user-id-cache")
public record UserIdCacheProperties(
        long maxSize
) {

    public UserIdCacheProperties(@DefaultValue("100000") long maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("grab.user-id-cache.max-size 값은 1 이상이어야 합니다.");
        }
        this.maxSize = maxSize;
    }
}
