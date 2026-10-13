package org.example.grab.domain.seller.gateway.nts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 국세청 사업자등록 상태조회(공공데이터포털) 연동 설정(grab.nts.business-registration.*).
 *
 * @param serviceKey 공공데이터포털 서비스키(Decoding 키). 환경변수 DATA_GO_KR_SERVICE_KEY로만 주입하며
 *                   비어 있으면 조회를 시도하지 않고 검증을 생략한다(SELLER-005, API 장애와 같은 처리)
 */
@ConfigurationProperties("grab.nts.business-registration")
public record NtsBusinessRegistrationProperties(
        @DefaultValue("https://api.odcloud.kr") String baseUrl,
        String serviceKey,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout
) {

    // record 기본 toString은 서비스키 원문을 출력하므로 로그·예외 메시지에 새지 않도록 가린다.
    @Override
    public String toString() {
        return "NtsBusinessRegistrationProperties[baseUrl=" + baseUrl
                + ", serviceKey=" + (StringUtils.hasText(serviceKey) ? "masked" : "empty")
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
