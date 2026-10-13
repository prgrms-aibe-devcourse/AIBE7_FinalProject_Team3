package org.example.grab.domain.seller.gateway.nts;

import org.example.grab.domain.seller.gateway.BusinessRegistrationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NtsBusinessRegistrationVerifierTest {

    private static final String BASE_URL = "https://api.odcloud.kr";
    private static final String SERVICE_KEY = "test-service-key";
    private static final String BRN = "1234567890";
    private static final String STATUS_URI = BASE_URL + "/api/nts-businessman/v1/status?serviceKey=" + SERVICE_KEY;

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    @DisplayName("계속사업자(01)면 ACTIVE를 돌려준다")
    void returnsActiveForContinuingBusiness() {
        // given
        NtsBusinessRegistrationVerifier verifier = new NtsBusinessRegistrationVerifier(builder, SERVICE_KEY);
        server.expect(requestTo(STATUS_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"b_no\":[\"" + BRN + "\"]}"))
                .andRespond(withSuccess(statusResponse("01"), MediaType.APPLICATION_JSON));

        // when
        BusinessRegistrationStatus status = verifier.verify(BRN);

        // then
        server.verify();
        assertThat(status).isEqualTo(BusinessRegistrationStatus.ACTIVE);
    }

    @Test
    @DisplayName("휴업자(02)·폐업자(03)는 INACTIVE를 돌려준다")
    void returnsInactiveForClosedOrSuspendedBusiness() {
        NtsBusinessRegistrationVerifier suspended = new NtsBusinessRegistrationVerifier(builder, SERVICE_KEY);
        server.expect(requestTo(STATUS_URI))
                .andRespond(withSuccess(statusResponse("02"), MediaType.APPLICATION_JSON));

        assertThat(suspended.verify(BRN)).isEqualTo(BusinessRegistrationStatus.INACTIVE);
        server.verify();
    }

    @Test
    @DisplayName("등록되지 않은 사업자등록번호(빈 b_stt_cd)는 INACTIVE를 돌려준다")
    void returnsInactiveForUnknownNumber() {
        NtsBusinessRegistrationVerifier verifier = new NtsBusinessRegistrationVerifier(builder, SERVICE_KEY);
        server.expect(requestTo(STATUS_URI))
                .andRespond(withSuccess(statusResponse(""), MediaType.APPLICATION_JSON));

        assertThat(verifier.verify(BRN)).isEqualTo(BusinessRegistrationStatus.INACTIVE);
        server.verify();
    }

    @Test
    @DisplayName("타임아웃·5xx·응답 형식 오류는 UNAVAILABLE을 돌려준다")
    void returnsUnavailableOnFailure() {
        NtsBusinessRegistrationVerifier timeoutVerifier = new NtsBusinessRegistrationVerifier(builder, SERVICE_KEY);
        server.expect(requestTo(STATUS_URI))
                .andRespond(withException(new SocketTimeoutException("timeout")));
        assertThat(timeoutVerifier.verify(BRN)).isEqualTo(BusinessRegistrationStatus.UNAVAILABLE);
        server.verify();

        server.reset();
        NtsBusinessRegistrationVerifier serverErrorVerifier = new NtsBusinessRegistrationVerifier(builder, SERVICE_KEY);
        server.expect(requestTo(STATUS_URI)).andRespond(withServerError());
        assertThat(serverErrorVerifier.verify(BRN)).isEqualTo(BusinessRegistrationStatus.UNAVAILABLE);
        server.verify();

        server.reset();
        NtsBusinessRegistrationVerifier malformedVerifier = new NtsBusinessRegistrationVerifier(builder, SERVICE_KEY);
        server.expect(requestTo(STATUS_URI))
                .andRespond(withStatus(HttpStatus.OK).body("{\"status_code\":\"ERROR\",\"data\":[]}")
                        .contentType(MediaType.APPLICATION_JSON));
        assertThat(malformedVerifier.verify(BRN)).isEqualTo(BusinessRegistrationStatus.UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("data의 첫 항목이 null이면 예외 없이 UNAVAILABLE을 돌려준다")
    void returnsUnavailableWhenFirstItemIsNull() {
        NtsBusinessRegistrationVerifier verifier = new NtsBusinessRegistrationVerifier(builder, SERVICE_KEY);
        server.expect(requestTo(STATUS_URI))
                .andRespond(withStatus(HttpStatus.OK)
                        .body("{\"status_code\":\"OK\",\"data\":[null]}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(verifier.verify(BRN)).isEqualTo(BusinessRegistrationStatus.UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("01·02·03·빈 값이 아닌 알 수 없는 상태 코드는 INACTIVE로 단정하지 않고 UNAVAILABLE을 돌려준다")
    void returnsUnavailableForUnknownStatusCode() {
        NtsBusinessRegistrationVerifier verifier = new NtsBusinessRegistrationVerifier(builder, SERVICE_KEY);
        server.expect(requestTo(STATUS_URI))
                .andRespond(withSuccess(statusResponse("99"), MediaType.APPLICATION_JSON));

        assertThat(verifier.verify(BRN)).isEqualTo(BusinessRegistrationStatus.UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("상태 코드 필드가 아예 없으면(null) INACTIVE로 단정하지 않고 UNAVAILABLE을 돌려준다")
    void returnsUnavailableWhenStatusCodeFieldMissing() {
        NtsBusinessRegistrationVerifier verifier = new NtsBusinessRegistrationVerifier(builder, SERVICE_KEY);
        server.expect(requestTo(STATUS_URI))
                .andRespond(withStatus(HttpStatus.OK)
                        .body("{\"status_code\":\"OK\",\"data\":[{\"b_no\":\"" + BRN + "\"}]}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(verifier.verify(BRN)).isEqualTo(BusinessRegistrationStatus.UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("4xx 응답도 예외를 던지지 않고 UNAVAILABLE을 돌려준다(SELLER-005: 신청자가 설정 오류의 피해를 보면 안 됨)")
    void returnsUnavailableOn4xxWithoutPropagating() {
        NtsBusinessRegistrationVerifier verifier = new NtsBusinessRegistrationVerifier(builder, SERVICE_KEY);
        server.expect(requestTo(STATUS_URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(verifier.verify(BRN)).isEqualTo(BusinessRegistrationStatus.UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("서비스키에 +가 있으면 공백이 아니라 %2B로 인코딩해 요청한다")
    void encodesPlusInServiceKey() {
        // given
        String serviceKeyWithPlus = "abc+def";
        NtsBusinessRegistrationVerifier verifier = new NtsBusinessRegistrationVerifier(builder, serviceKeyWithPlus);
        server.expect(requestTo(BASE_URL + "/api/nts-businessman/v1/status?serviceKey=abc%2Bdef"))
                .andRespond(withSuccess(statusResponse("01"), MediaType.APPLICATION_JSON));

        // when, then
        assertThat(verifier.verify(BRN)).isEqualTo(BusinessRegistrationStatus.ACTIVE);
        server.verify();
    }

    @Test
    @DisplayName("서비스키가 없으면 조회를 시도하지 않고 UNAVAILABLE을 돌려준다")
    void returnsUnavailableWhenNotConfigured() {
        NtsBusinessRegistrationVerifier verifier = new NtsBusinessRegistrationVerifier(builder, "");

        assertThat(verifier.verify(BRN)).isEqualTo(BusinessRegistrationStatus.UNAVAILABLE);
        server.verify();
    }

    private static String statusResponse(String businessStatusCode) {
        return """
                {
                  "status_code": "OK",
                  "match_cnt": 1,
                  "request_cnt": 1,
                  "data": [
                    {
                      "b_no": "%s",
                      "b_stt_cd": "%s"
                    }
                  ]
                }
                """.formatted(BRN, businessStatusCode);
    }
}
