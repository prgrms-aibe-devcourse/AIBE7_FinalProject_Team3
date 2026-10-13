package org.example.grab.domain.seller.gateway.nts;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.seller.gateway.BusinessRegistrationStatus;
import org.example.grab.domain.seller.gateway.BusinessRegistrationVerifier;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/*
    국세청 사업자등록 상태조회(공공데이터포털, 진위확인 API 아님) 연동(GR-90, SELLER-005).
    재시도·서킷브레이커는 두지 않는다. 장애·타임아웃은 즉시 UNAVAILABLE로 돌려
    호출부(판매자 신청 서비스)가 검증을 생략하고 PENDING으로 접수하게 한다.
 */
@Slf4j
public class NtsBusinessRegistrationVerifier implements BusinessRegistrationVerifier {

    private static final String STATUS_PATH = "/api/nts-businessman/v1/status";

    // b_stt_cd: 02 휴업자, 03 폐업자, 빈 값은 등록되지 않은 사업자등록번호. 이 외의 값은 응답 형식이 바뀌었거나
    // 이상 코드일 가능성이 있어 INACTIVE로 단정하지 않고 UNAVAILABLE로 돌려 신청자가 불이익을 보지 않게 한다.
    private static final Set<String> KNOWN_INACTIVE_CODES = Set.of("02", "03", "");

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
            // serviceKey를 URI 템플릿 변수로 넘겨 build()가 확장한다. queryParam에 문자열을 그대로 넣으면
            // '+'가 쿼리에서 유효한 문자로 취급돼 %2B로 인코딩되지 않고, 이를 공백으로 해석하는 서버에서 키가 깨진다.
            NtsBusinessStatusResponse response = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(STATUS_PATH).queryParam("serviceKey", "{serviceKey}")
                            .build(serviceKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("b_no", List.of(businessRegistrationNumber)))
                    .retrieve()
                    .body(NtsBusinessStatusResponse.class);
            return toStatus(response);
        } catch (RestClientException e) {
            // 타임아웃·연결 실패·4xx/5xx·응답 해석 실패. SELLER-005에 따라 원인과 무관하게 예외를 던지지 않고
            // 검증을 생략하게 UNAVAILABLE로 돌려준다(신청자가 우리 쪽 설정 오류의 피해를 보면 안 됨).
            // 다만 4xx(서비스키 오류 등 우리 쪽 설정 문제 가능성)는 조용히 묻히지 않도록 error로 남긴다.
            if (e instanceof RestClientResponseException responseException
                    && responseException.getStatusCode().is4xxClientError()) {
                log.error("국세청 사업자등록 상태조회 4xx 응답. 서비스키·요청 형식을 확인해야 함: status={}",
                        responseException.getStatusCode().value());
            } else {
                log.warn("국세청 사업자등록 상태조회 실패: cause={}", e.getClass().getSimpleName());
            }
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
        if (item == null) {
            log.warn("국세청 사업자등록 상태조회 응답의 첫 항목이 비어 있음");
            return BusinessRegistrationStatus.UNAVAILABLE;
        }
        String statusCode = item.statusCode();
        if (NtsBusinessStatusResponse.Item.ACTIVE_CODE.equals(statusCode)) {
            return BusinessRegistrationStatus.ACTIVE;
        }
        if (statusCode != null && KNOWN_INACTIVE_CODES.contains(statusCode)) {
            log.info("계속사업자가 아닌 사업자등록번호 신청 시도: statusCode={}", statusCode);
            return BusinessRegistrationStatus.INACTIVE;
        }
        log.warn("국세청 사업자등록 상태조회 응답에 알 수 없는 상태 코드: statusCode={}", statusCode);
        return BusinessRegistrationStatus.UNAVAILABLE;
    }
}
