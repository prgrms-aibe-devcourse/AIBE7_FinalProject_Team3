package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.domain.payment.dto.PaymentHistoryResponse;
import org.example.grab.domain.payment.dto.SellerPaymentHistoryResponse;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.repository.PaymentRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// 실제 PostgreSQL에서 결제 이력 조회의 권한 판정과 시도 순서를 확인한다(PAYMENT.md 1.3·1.4).
// 조회만 하므로 테스트 트랜잭션으로 묶고 끝나면 되돌린다.
@Transactional
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentHistoryIntegrationTest {

    private static final OffsetDateTime APPROVED_AT =
            OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    private static final OffsetDateTime CANCELED_AT = APPROVED_AT.plusMinutes(30);

    @Autowired
    private PaymentHistoryService paymentHistoryService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PaymentTestFixture fixture;
    private Order order;

    @BeforeEach
    void setUp() {
        fixture = new PaymentTestFixture(jdbcTemplate, orderRepository, orderItemRepository, stockReservationRepository);
        order = fixture.createOrder();
    }

    @Test
    @DisplayName("구매자는 실패·승인 후 취소·승인 순으로 쌓인 결제 이력을 시도 순서대로 받는다")
    void findsBuyerHistoryInAttemptOrder() {
        // given
        Payment failed = savePayment(order, payment -> payment.fail("REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다."));
        Payment canceled = savePayment(order, payment -> {
            payment.succeed(APPROVED_AT);
            payment.cancel(CANCELED_AT);
        });
        Payment succeeded = savePayment(order, payment -> payment.succeed(APPROVED_AT.plusHours(1)));

        // when
        List<PaymentHistoryResponse> result = paymentHistoryService.findBuyerHistory(fixture.buyerId(), order.getUuid());

        // then
        assertThat(result).extracting(PaymentHistoryResponse::paymentId)
                .containsExactly(failed.getUuid(), canceled.getUuid(), succeeded.getUuid());
        assertThat(result).allSatisfy(history -> {
            assertThat(history.orderId()).isEqualTo(order.getUuid());
            assertThat(history.requestedAt()).isNotNull();
        });
        assertThat(result.get(0)).satisfies(history -> {
            assertThat(history.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(history.paidAt()).isNull();
            assertThat(history.failure().code()).isEqualTo("REJECT_CARD_PAYMENT");
        });
        assertThat(result.get(1)).satisfies(history -> {
            assertThat(history.status()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(history.paidAt()).isAtSameInstantAs(APPROVED_AT);
            assertThat(history.canceledAt()).isAtSameInstantAs(CANCELED_AT);
            assertThat(history.failure()).isNull();
        });
        assertThat(result.get(2)).satisfies(history -> {
            assertThat(history.status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(history.paidAt()).isAtSameInstantAs(APPROVED_AT.plusHours(1));
            assertThat(history.canceledAt()).isNull();
        });
    }

    @Test
    @DisplayName("다른 주문의 결제는 섞이지 않고, 결제 시도가 없는 주문은 빈 목록이다")
    void returnsOnlyOwnOrderPayments() {
        // given
        Order otherOrder = fixture.createOrder();
        savePayment(otherOrder, payment -> payment.succeed(APPROVED_AT));

        // when
        List<PaymentHistoryResponse> result = paymentHistoryService.findBuyerHistory(fixture.buyerId(), order.getUuid());

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("다른 구매자의 주문은 ORDER_NOT_FOUND로 거부한다")
    void rejectsOtherBuyer() {
        // given
        savePayment(order, payment -> payment.succeed(APPROVED_AT));
        Long otherBuyerId = insertUser();

        // when
        Throwable thrown = catchThrowable(() -> paymentHistoryService.findBuyerHistory(otherBuyerId, order.getUuid()));

        // then
        assertErrorCode(thrown, OrderErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("판매자는 본인 DROP 주문의 결제 이력을 시도 순서대로 받는다")
    void findsSellerHistory() {
        // given
        Payment failed = savePayment(order, payment -> payment.fail("REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다."));
        Payment succeeded = savePayment(order, payment -> payment.succeed(APPROVED_AT));

        // when
        List<SellerPaymentHistoryResponse> result =
                paymentHistoryService.findSellerHistory(fixture.sellerId(), order.getUuid());

        // then
        assertThat(result).extracting(SellerPaymentHistoryResponse::paymentId)
                .containsExactly(failed.getUuid(), succeeded.getUuid());
        assertThat(result).extracting(SellerPaymentHistoryResponse::status)
                .containsExactly(PaymentStatus.FAILED, PaymentStatus.SUCCEEDED);
        assertThat(result.get(1).paidAt()).isAtSameInstantAs(APPROVED_AT);
    }

    @Test
    @DisplayName("다른 판매자의 DROP 주문은 ORDER_ACCESS_DENIED로 거부한다")
    void rejectsOtherSeller() {
        // given
        Long otherSellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '다른 판매자', ?) RETURNING id",
                Long.class, insertUser(), "other-seller-" + UUID.randomUUID() + "@example.com");

        // when
        Throwable thrown = catchThrowable(() -> paymentHistoryService.findSellerHistory(otherSellerId, order.getUuid()));

        // then
        assertErrorCode(thrown, OrderErrorCode.ORDER_ACCESS_DENIED);
    }

    @Test
    @DisplayName("없는 주문은 판매자에게도 ORDER_NOT_FOUND로 응답한다")
    void rejectsMissingOrderForSeller() {
        // when
        Throwable thrown = catchThrowable(() -> paymentHistoryService.findSellerHistory(fixture.sellerId(), UUID.randomUUID()));

        // then
        assertErrorCode(thrown, OrderErrorCode.ORDER_NOT_FOUND);
    }

    private Payment savePayment(Order target, Consumer<Payment> result) {
        Payment payment = Payment.request(target.getId(), PaymentProvider.TOSS, UUID.randomUUID().toString(),
                "a".repeat(64), "payment-key-" + UUID.randomUUID(), PaymentTestFixture.TOTAL_AMOUNT);
        result.accept(payment);
        return paymentRepository.saveAndFlush(payment);
    }

    private Long insertUser() {
        String uniqueValue = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded', ?) RETURNING id",
                Long.class, uniqueValue + "@example.com", "u" + uniqueValue.substring(0, 8));
    }

    private static void assertErrorCode(Throwable thrown, OrderErrorCode errorCode) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
