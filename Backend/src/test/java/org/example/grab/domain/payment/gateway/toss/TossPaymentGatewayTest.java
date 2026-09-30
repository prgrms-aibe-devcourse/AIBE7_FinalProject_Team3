package org.example.grab.domain.payment.gateway.toss;

import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TossPaymentGatewayTest {

    private static final String BASE_URL = "https://api.tosspayments.com";
    private static final String SECRET_KEY = "test_sk_dummy";
    private static final PaymentConfirmCommand COMMAND =
            new PaymentConfirmCommand("payment-key", "ORD-20260929-000001", 33000, "server-idempotency-key");

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    @DisplayName("승인 요청에 Basic 인증·서버 멱등 키·승인 정보를 담고 DONE이면 승인 완료로 돌려준다")
    void confirmApproved() {
        // given
        TossPaymentGateway gateway = new TossPaymentGateway(builder, SECRET_KEY);
        String expectedAuthorization = "Basic " + Base64.getEncoder()
                .encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", expectedAuthorization))
                .andExpect(header("Idempotency-Key", "server-idempotency-key"))
                .andExpect(jsonPath("$.paymentKey").value("payment-key"))
                .andExpect(jsonPath("$.orderId").value("ORD-20260929-000001"))
                .andExpect(jsonPath("$.amount").value(33000))
                .andRespond(withSuccess(payment("DONE", "\"2026-09-29T21:00:00+09:00\""), MediaType.APPLICATION_JSON));

        // when
        PaymentGatewayResult result = gateway.confirm(COMMAND);

        // then
        server.verify();
        assertThat(result.outcome()).isEqualTo(Outcome.APPROVED);
        assertThat(result.pgStatus()).isEqualTo("DONE");
        assertThat(result.orderId()).isEqualTo("ORD-20260929-000001");
        assertThat(result.totalAmount()).isEqualTo(33000L);
        assertThat(result.approvedAt()).isEqualTo(OffsetDateTime.parse("2026-09-29T21:00:00+09:00"));
    }

    @Test
    @DisplayName("카드 거절처럼 토스가 명확히 거절하면 미승인과 실패 코드를 돌려준다")
    void confirmRejected() {
        // given
        TossPaymentGateway gateway = new TossPaymentGateway(builder, SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body(error("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다.")));

        // when
        PaymentGatewayResult result = gateway.confirm(COMMAND);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_APPROVED);
        assertThat(result.failureCode()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(result.failureMessage()).isEqualTo("한도초과 혹은 잔액부족으로 결제에 실패했습니다.");
    }

    @Test
    @DisplayName("같은 주문번호의 승인 이력이 있다는 오류는 이 요청이 승인되지 않은 것이므로 미승인으로 돌려준다")
    void confirmDuplicatedOrderId() {
        // given: 2026-09-30 테스트 환경에서 이미 승인된 주문번호로 다른 결제를 승인하려 할 때 받은 응답
        TossPaymentGateway gateway = new TossPaymentGateway(builder, SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(error("DUPLICATED_ORDER_ID", "이미 승인 및 취소가 진행된 중복된 주문번호 입니다.")));

        // when
        PaymentGatewayResult result = gateway.confirm(COMMAND);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_APPROVED);
        assertThat(result.failureCode()).isEqualTo("DUPLICATED_ORDER_ID");
    }

    @Test
    @DisplayName("이미 처리됐거나 처리 중일 수 있는 4xx 오류는 실패로 단정하지 않고 결과 불명으로 돌려준다")
    void confirmUncertainError() {
        // given
        TossPaymentGateway gateway = new TossPaymentGateway(builder, SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(error("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.")));

        // when
        PaymentGatewayResult result = gateway.confirm(COMMAND);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(result.failureCode()).isEqualTo("ALREADY_PROCESSED_PAYMENT");
    }

    @Test
    @DisplayName("토스 5xx 오류는 결과 불명으로 돌려준다")
    void confirmServerError() {
        // given
        TossPaymentGateway gateway = new TossPaymentGateway(builder, SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body(error("FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 시스템 처리 작업이 실패했습니다.")));

        // when
        PaymentGatewayResult result = gateway.confirm(COMMAND);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    @DisplayName("응답 타임아웃은 예외를 던지지 않고 결과 불명으로 돌려준다")
    void confirmTimeout() {
        // given
        TossPaymentGateway gateway = new TossPaymentGateway(builder, SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        // when
        PaymentGatewayResult result = gateway.confirm(COMMAND);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    @DisplayName("승인 응답 상태가 종료 상태면 미승인, 진행 중 상태면 승인 대기 표시가 붙은 결과 불명으로 돌려준다")
    void confirmNonDoneStatus() {
        // given
        TossPaymentGateway gateway = new TossPaymentGateway(builder, SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess(payment("ABORTED", "null"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess(payment("IN_PROGRESS", "null"), MediaType.APPLICATION_JSON));

        // when
        PaymentGatewayResult aborted = gateway.confirm(COMMAND);
        PaymentGatewayResult inProgress = gateway.confirm(COMMAND);

        // then
        assertThat(aborted.outcome()).isEqualTo(Outcome.NOT_APPROVED);
        assertThat(aborted.pgStatus()).isEqualTo("ABORTED");
        assertThat(aborted.awaitingConfirmation()).isFalse();
        assertThat(inProgress.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(inProgress.pgStatus()).isEqualTo("IN_PROGRESS");
        // 인증만 끝나고 승인 요청을 받지 않은 상태라 같은 멱등 키로 다시 승인을 요청할 수 있다.
        assertThat(inProgress.awaitingConfirmation()).isTrue();
    }

    @Test
    @DisplayName("결제 조회는 DONE이면 승인 완료, 존재하지 않는 결제면 미승인, 5xx면 결과 불명으로 돌려준다")
    void lookup() {
        // given
        TossPaymentGateway gateway = new TossPaymentGateway(builder, SECRET_KEY);
        server.expect(requestTo(BASE_URL + "/v1/payments/key-done"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(payment("DONE", "\"2026-09-29T21:00:00+09:00\""), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/payments/key-missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(error("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.")));
        server.expect(requestTo(BASE_URL + "/v1/payments/key-error"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // when
        PaymentGatewayResult done = gateway.lookup("key-done");
        PaymentGatewayResult missing = gateway.lookup("key-missing");
        PaymentGatewayResult failed = gateway.lookup("key-error");

        // then
        server.verify();
        assertThat(done.outcome()).isEqualTo(Outcome.APPROVED);
        assertThat(missing.outcome()).isEqualTo(Outcome.NOT_APPROVED);
        assertThat(missing.failureCode()).isEqualTo("NOT_FOUND_PAYMENT");
        assertThat(failed.outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    @DisplayName("시크릿 키가 없으면 토스를 호출하지 않고 승인은 미승인, 조회는 결과 불명으로 돌려준다")
    void notConfigured() {
        // given
        TossPaymentGateway gateway = new TossPaymentGateway(builder, " ");

        // when
        PaymentGatewayResult confirm = gateway.confirm(COMMAND);
        PaymentGatewayResult lookup = gateway.lookup("payment-key");

        // then
        server.verify();
        assertThat(confirm.outcome()).isEqualTo(Outcome.NOT_APPROVED);
        assertThat(confirm.failureCode()).isEqualTo(TossPaymentGateway.NOT_CONFIGURED_CODE);
        assertThat(lookup.outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    @DisplayName("설정과 승인 요청의 문자열 표현에 시크릿 키와 paymentKey 원문을 담지 않는다")
    void masksSecrets() {
        // given
        TossPaymentsProperties properties = new TossPaymentsProperties(
                BASE_URL, SECRET_KEY, Duration.ofSeconds(3), Duration.ofSeconds(10));

        // when & then
        assertThat(properties.toString()).doesNotContain(SECRET_KEY).contains("masked");
        assertThat(COMMAND.toString()).doesNotContain("payment-key").doesNotContain("server-idempotency-key");
    }

    private static String payment(String status, String approvedAt) {
        return """
                {"paymentKey":"payment-key","orderId":"ORD-20260929-000001","status":"%s",
                 "totalAmount":33000,"approvedAt":%s,"method":"카드","card":{"number":"12345678****000*"}}
                """.formatted(status, approvedAt);
    }

    private static String error(String code, String message) {
        return """
                {"code":"%s","message":"%s"}
                """.formatted(code, message);
    }
}
