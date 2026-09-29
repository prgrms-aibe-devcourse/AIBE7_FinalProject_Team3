package org.example.grab.domain.payment.gateway.toss;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/*
    토스페이먼츠 결제 승인·조회 API 호출(TECHSTACK.md 4.4).
    승인하지 않았음이 확실한 경우만 NOT_APPROVED로 돌려주고, 판단이 애매하면 UNKNOWN으로 돌려 결제 서비스가 조회로 다시 확인하게 한다.
    UNKNOWN을 NOT_APPROVED로 잘못 판정하면 사용자가 재결제해 이중 결제가 생기므로, 분류가 애매하면 UNKNOWN 쪽을 고른다.
 */
@Slf4j
public class TossPaymentGateway implements PaymentGateway {

    static final String NOT_CONFIGURED_CODE = "PG_NOT_CONFIGURED";

    private static final String APPROVED_STATUS = "DONE";

    // 결제가 진행되지 않고 끝난 상태. 이 상태의 결제는 다시 승인되지 않는다.
    private static final Set<String> TERMINATED_STATUSES = Set.of("ABORTED", "EXPIRED", "CANCELED");

    // 4xx지만 이미 승인됐거나 처리 중일 수 있는 오류. 실패로 단정하지 않고 조회로 확인한다.
    private static final Set<String> UNCERTAIN_ERROR_CODES = Set.of(
            "ALREADY_PROCESSED_PAYMENT",
            "PROVIDER_ERROR",
            "IDEMPOTENT_REQUEST_PROCESSING"
    );

    private static final String NOT_FOUND_PAYMENT_CODE = "NOT_FOUND_PAYMENT";

    private final RestClient restClient;
    private final boolean configured;

    // requestFactory(타임아웃)와 baseUrl은 설정 클래스가 builder에 넣는다. 테스트는 같은 builder에 MockRestServiceServer를 묶는다.
    public TossPaymentGateway(RestClient.Builder restClientBuilder, String secretKey) {
        this.configured = secretKey != null && !secretKey.isBlank();
        RestClient.Builder builder = restClientBuilder.clone();
        if (configured) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, basicAuthorization(secretKey));
        }
        this.restClient = builder.build();
    }

    @Override
    public PaymentGatewayResult confirm(PaymentConfirmCommand command) {
        if (!configured) {
            log.error("토스페이먼츠 시크릿 키가 설정되지 않아 결제 승인을 시도하지 않음: orderId={}", command.orderId());
            return PaymentGatewayResult.notApproved(null, NOT_CONFIGURED_CODE, "결제 PG가 설정되지 않았습니다.");
        }
        try {
            return restClient.post()
                    .uri("/v1/payments/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.idempotencyKey())
                    .body(Map.of(
                            "paymentKey", command.paymentKey(),
                            "orderId", command.orderId(),
                            "amount", command.amount()
                    ))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return fromPayment(response.bodyTo(TossPaymentResponse.class));
                        }
                        return fromConfirmError(response.getStatusCode(), readError(response));
                    });
        } catch (RestClientException e) {
            // 타임아웃·연결 실패·응답 해석 실패. 토스에서 승인이 끝났을 수 있으므로 실패로 단정하지 않는다.
            log.warn("토스페이먼츠 결제 승인 결과 불명: orderId={}, cause={}", command.orderId(), e.getClass().getSimpleName());
            return PaymentGatewayResult.unknown(null, null, "결제 승인 응답을 확인하지 못했습니다.");
        }
    }

    @Override
    public PaymentGatewayResult lookup(String paymentKey) {
        if (!configured) {
            log.error("토스페이먼츠 시크릿 키가 설정되지 않아 결제 조회를 시도하지 않음");
            return PaymentGatewayResult.unknown(null, NOT_CONFIGURED_CODE, "결제 PG가 설정되지 않았습니다.");
        }
        try {
            return restClient.get()
                    .uri("/v1/payments/{paymentKey}", paymentKey)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return fromPayment(response.bodyTo(TossPaymentResponse.class));
                        }
                        return fromLookupError(response.getStatusCode(), readError(response));
                    });
        } catch (RestClientException e) {
            log.warn("토스페이먼츠 결제 조회 실패: cause={}", e.getClass().getSimpleName());
            return PaymentGatewayResult.unknown(null, null, "결제 상태를 확인하지 못했습니다.");
        }
    }

    private PaymentGatewayResult fromPayment(TossPaymentResponse payment) {
        if (payment == null || payment.status() == null) {
            return PaymentGatewayResult.unknown(null, null, "결제 응답에 상태가 없습니다.");
        }
        if (APPROVED_STATUS.equals(payment.status())) {
            if (payment.approvedAt() == null) {
                return PaymentGatewayResult.unknown(payment.status(), null, "승인 완료 응답에 승인 시각이 없습니다.");
            }
            return PaymentGatewayResult.approved(
                    payment.status(), payment.orderId(), payment.totalAmount(), payment.approvedAt());
        }
        if (TERMINATED_STATUSES.contains(payment.status())) {
            return PaymentGatewayResult.notApproved(payment.status(), payment.status(), "결제가 완료되지 않았습니다.");
        }
        // READY, IN_PROGRESS, PARTIAL_CANCELED 등: 승인 여부를 이 응답만으로 정할 수 없다.
        return PaymentGatewayResult.unknown(payment.status(), null, "결제가 아직 확정되지 않았습니다.");
    }

    private PaymentGatewayResult fromConfirmError(HttpStatusCode status, TossErrorResponse error) {
        if (status.is5xxServerError() || error.code() == null || UNCERTAIN_ERROR_CODES.contains(error.code())) {
            log.warn("토스페이먼츠 결제 승인 결과 불명: status={}, code={}", status.value(), error.code());
            return PaymentGatewayResult.unknown(null, error.code(), error.message());
        }
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            log.error("토스페이먼츠 인증 실패. 시크릿 키 설정을 확인해야 함: code={}", error.code());
        }
        return PaymentGatewayResult.notApproved(null, error.code(), error.message());
    }

    private PaymentGatewayResult fromLookupError(HttpStatusCode status, TossErrorResponse error) {
        if (status.value() == HttpStatus.NOT_FOUND.value() && NOT_FOUND_PAYMENT_CODE.equals(error.code())) {
            return PaymentGatewayResult.notApproved(null, error.code(), error.message());
        }
        log.warn("토스페이먼츠 결제 조회 실패: status={}, code={}", status.value(), error.code());
        return PaymentGatewayResult.unknown(null, error.code(), error.message());
    }

    private TossErrorResponse readError(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            TossErrorResponse error = response.bodyTo(TossErrorResponse.class);
            return error == null ? new TossErrorResponse(null, null) : error;
        } catch (RestClientException e) {
            return new TossErrorResponse(null, null);
        }
    }

    // 시크릿 키 뒤에 콜론을 붙여 Base64로 인코딩한다(토스페이먼츠 Basic 인증 규칙).
    private static String basicAuthorization(String secretKey) {
        String credentials = secretKey + ":";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
