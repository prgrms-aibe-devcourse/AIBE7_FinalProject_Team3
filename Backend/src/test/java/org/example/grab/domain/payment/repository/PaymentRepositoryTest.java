package org.example.grab.domain.payment.repository;

import jakarta.persistence.EntityManager;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentEvent;
import org.example.grab.domain.payment.entity.PaymentEventResult;
import org.example.grab.domain.payment.entity.PaymentEventSource;
import org.example.grab.domain.payment.entity.PaymentEventType;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.entity.ReconciliationStatus;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long orderId;

    @BeforeEach
    void setUp() {
        String uniqueValue = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', ?)
                RETURNING id
                """,
                Long.class,
                uniqueValue + "@example.com",
                "p" + uniqueValue.substring(0, 8)
        );
        Long sellerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?)
                RETURNING id
                """,
                Long.class,
                userId,
                "seller-" + uniqueValue + "@example.com"
        );
        Long dropId = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id) VALUES (?) RETURNING id",
                Long.class,
                sellerId
        );
        orderId = jdbcTemplate.queryForObject(
                """
                INSERT INTO orders (order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
                                    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
                                    total_amount, recipient_name, recipient_phone, postal_code, address_line1,
                                    payment_expires_at)
                VALUES (?, ?, ?, ?, repeat('a', 64), 'PAYMENT_PENDING', '한정판 후드', 'GRAB 판매자', 30000, 3000,
                        33000, '홍길동', '010-1234-5678', '06236', '서울시 강남구', CURRENT_TIMESTAMP + INTERVAL '15 minutes')
                RETURNING id
                """,
                Long.class,
                "ORD-PAY-" + uniqueValue,
                userId,
                dropId,
                "order-key-" + uniqueValue
        );
    }

    @Test
    @DisplayName("결제를 저장하고 주문과 클라이언트 멱등 키로 조회한다")
    void saveAndFindByClientIdempotencyKey() {
        // given
        Payment payment = Payment.request(orderId, PaymentProvider.TOSS, "client-key", "b".repeat(64), "payment-key", 33000);

        // when
        paymentRepository.saveAndFlush(payment);
        entityManager.clear();

        // then
        Payment found = paymentRepository.findByOrderIdAndClientIdempotencyKey(orderId, "client-key").orElseThrow();
        assertThat(found.getUuid()).isEqualTo(payment.getUuid());
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(found.getReconciliationStatus()).isEqualTo(ReconciliationStatus.NONE);
        assertThat(found.getIdempotencyKey()).isEqualTo(payment.getIdempotencyKey());
        assertThat(found.getRequestHash()).isEqualTo("b".repeat(64));
        assertThat(found.getProviderPaymentId()).isEqualTo("payment-key");
        assertThat(paymentRepository.existsByProviderAndProviderPaymentId(PaymentProvider.TOSS, "payment-key")).isTrue();
    }

    @Test
    @DisplayName("진행 중인 결제(PENDING, UNKNOWN)만 골라 조회한다")
    void findInProgressPayments() {
        // given
        Payment pending = Payment.request(orderId, PaymentProvider.TOSS, "key-1", "c".repeat(64), "payment-1", 33000);
        Payment unknown = Payment.request(orderId, PaymentProvider.TOSS, "key-2", "c".repeat(64), "payment-2", 33000);
        Payment failed = Payment.request(orderId, PaymentProvider.TOSS, "key-3", "c".repeat(64), "payment-3", 33000);
        unknown.markUnknown("승인 응답 타임아웃");
        failed.fail("REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다.");
        paymentRepository.saveAllAndFlush(List.of(pending, unknown, failed));
        entityManager.clear();

        // when
        List<Payment> inProgress = paymentRepository.findByOrderIdAndStatusInOrderByIdAsc(
                orderId, List.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN));

        // then
        assertThat(inProgress).extracting(Payment::getClientIdempotencyKey).containsExactly("key-1", "key-2");
    }

    @Test
    @DisplayName("같은 주문에 같은 클라이언트 멱등 키로 결제를 두 번 저장할 수 없다")
    void rejectsDuplicateClientIdempotencyKey() {
        // given
        paymentRepository.saveAndFlush(
                Payment.request(orderId, PaymentProvider.TOSS, "same-key", "d".repeat(64), "payment-a", 33000));

        // when & then
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(
                Payment.request(orderId, PaymentProvider.TOSS, "same-key", "e".repeat(64), "payment-b", 33000)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 paymentKey로 결제를 두 번 저장할 수 없다")
    void rejectsDuplicatePaymentKey() {
        // given
        paymentRepository.saveAndFlush(
                Payment.request(orderId, PaymentProvider.TOSS, "key-a", "f".repeat(64), "same-payment", 33000));

        // when & then
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(
                Payment.request(orderId, PaymentProvider.TOSS, "key-b", "f".repeat(64), "same-payment", 33000)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("결제 이벤트를 JSON 페이로드와 함께 저장하고 같은 이벤트 키는 거부한다")
    void saveEventAndRejectDuplicateKey() {
        // given
        Payment payment = paymentRepository.saveAndFlush(
                Payment.request(orderId, PaymentProvider.TOSS, "event-key", "g".repeat(64), "payment-event", 33000));
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-29T12:00:00Z");

        // when
        paymentEventRepository.saveAndFlush(PaymentEvent.record(
                payment.getId(), "confirm", PaymentEventType.CONFIRM, PaymentEventSource.API,
                PaymentEventResult.APPLIED, "{\"status\":\"DONE\",\"totalAmount\":33000}", occurredAt));
        entityManager.clear();

        // then
        assertThat(paymentEventRepository.existsByPaymentIdAndEventKey(payment.getId(), "confirm")).isTrue();
        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT payload ->> 'status' FROM payment_events WHERE payment_id = ?", String.class, payment.getId());
        assertThat(storedStatus).isEqualTo("DONE");
        assertThatThrownBy(() -> paymentEventRepository.saveAndFlush(PaymentEvent.record(
                payment.getId(), "confirm", PaymentEventType.CONFIRM, PaymentEventSource.API,
                PaymentEventResult.APPLIED, "{}", occurredAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
