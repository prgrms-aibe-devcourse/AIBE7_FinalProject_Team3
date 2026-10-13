package org.example.grab.domain.seller.gateway.nts;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.seller.gateway.BusinessRegistrationStatus;
import org.example.grab.domain.seller.gateway.BusinessRegistrationVerifier;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/*
    국세청 사업자등록 상태조회(공공데이터포털, 진위확인 API 아님) 연동(GR-90, SELLER-005).
    재시도·서킷브레이커는 두지 않는다. 장애·타임아웃은 즉시 UNAVAILABLE로 돌려
    호출부(판매자 신청 서비스)가 검증을 생략하고 PENDING으로 접수하게 한다.
 */
@Slf4j
public class NtsBusinessRegistrationVerifier implements BusinessRegistrationVerifier {

    private static final String STATUS_PATH = "/api/nts-businessman/v1/status";

    private final RestClient restClient;
    private final String serviceKey;
    private final boolean configured;

    // requestFactory(타임아웃)와 baseUrl은 설정 클래스가 builder에 넣는다. 테스트는 같은 builder에 MockRestServiceServer를 묶는다.
    public NtsBusinessRegistrationVerifier(RestClient.Builder restClientBuilder, String serviceKey) {
        this.configured = StringUtils.hasText(serviceKey);
        this.serviceKey = serviceKey;
        this.restClient = restClientBuilder.clone().build();
    }

    @Override
    public BusinessRegistrationStatus verify(String businessRegistrationNumber) {
        if (!configured) {
            log.warn("국세청 서비스키가 설정되지 않아 사업자등록 상태조회를 시도하지 않음");
            return BusinessRegistrationStatus.UNAVAILABLE;
        }
        try {
            NtsBusinessStatusResponse response = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(STATUS_PATH).queryParam("serviceKey", serviceKey).build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("b_no", List.of(businessRegistrationNumber)))
                    .retrieve()
                    .body(NtsBusinessStatusResponse.class);
            return toStatus(response);
        } catch (RestClientException e) {
            // 타임아웃·연결 실패·4xx/5xx·응답 해석 실패. 실패로 단정하지 않고 검증을 생략하게 UNAVAILABLE로 돌려준다.
            log.warn("국세청 사업자등록 상태조회 실패: cause={}", e.getClass().getSimpleName());
            return BusinessRegistrationStatus.UNAVAILABLE;
        }
    }

    private BusinessRegistrationStatus toStatus(NtsBusinessStatusResponse response) {
        if (response == null || !NtsBusinessStatusResponse.OK_STATUS_CODE.equals(response.statusCode())
                || response.data() == null || response.data().isEmpty()) {
            log.warn("국세청 사업자등록 상태조회 응답 형식이 올바르지 않음");
            return BusinessRegistrationStatus.UNAVAILABLE;
        }
        NtsBusinessStatusResponse.Item item = response.data().get(0);
        if (NtsBusinessStatusResponse.Item.ACTIVE_CODE.equals(item.statusCode())) {
            return BusinessRegistrationStatus.ACTIVE;
        }
        log.info("계속사업자가 아닌 사업자등록번호 신청 시도: statusCode={}", item.statusCode());
        return BusinessRegistrationStatus.INACTIVE;
    }
}
