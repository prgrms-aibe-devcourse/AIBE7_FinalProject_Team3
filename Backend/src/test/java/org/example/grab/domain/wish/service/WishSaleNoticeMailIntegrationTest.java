package org.example.grab.domain.wish.service;

import org.example.grab.domain.drop.event.DropGrabStartedEvent;
import org.example.grab.domain.drop.event.DropSaleStartingSoonEvent;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.service.AsyncEmailDispatcher;
import org.example.grab.domain.wish.dto.WishMailRecipientProjection;
import org.example.grab.domain.wish.repository.WishRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * GR-69 판매 임박·판매 시작 메일의 수신자 조회와 발송 시점 검증.
 * 활성 WISH와 ACTIVE 회원만 걸러지는지는 실제 PostgreSQL로, 커밋된 전환에만 메일이 나가는지는
 * 전환 이벤트를 트랜잭션 안에서 발행해 확인한다. SMTP는 범위 밖이라 발송 창구를 대역으로 둔다.
 */
@SpringBootTest
@Testcontainers
class WishSaleNoticeMailIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WishRepository wishRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AsyncEmailDispatcher emailDispatcher;

    private Long sellerId;

    private Long sellerUserId;

    @BeforeEach
    void setUp() {
        sellerId = insertSeller();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                "DELETE FROM wishes WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?)", sellerId);
        jdbcTemplate.update("DELETE FROM drops WHERE seller_id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", emailPrefix() + "%");
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", sellerUserId);
    }

    @Test
    @DisplayName("여러 DROP을 한 번에 조회하면 각 DROP의 활성 WISH 수신자를 DROP 이름과 함께 반환한다")
    void findsRecipientsForEachDrop() {
        // given
        Long sneakers = insertDrop("한정판 스니커즈");
        Long hoodie = insertDrop("한정판 후디");
        Long email1 = insertUser("ACTIVE");
        Long email2 = insertUser("ACTIVE");
        insertWish(email1, sneakers, true);
        insertWish(email2, sneakers, true);
        insertWish(email1, hoodie, true);

        // when
        List<WishMailRecipientProjection> recipients =
                wishRepository.findSaleStartMailRecipients(List.of(sneakers, hoodie));

        // then
        assertThat(recipients)
                .extracting(WishMailRecipientProjection::getDropId, WishMailRecipientProjection::getDropName,
                        WishMailRecipientProjection::getEmail)
                .containsExactlyInAnyOrder(
                        tuple(sneakers, "한정판 스니커즈", emailOf(email1)),
                        tuple(sneakers, "한정판 스니커즈", emailOf(email2)),
                        tuple(hoodie, "한정판 후디", emailOf(email1)));
    }

    @Test
    @DisplayName("취소한 WISH와 ACTIVE가 아닌 회원은 수신자에서 제외한다")
    void excludesCanceledWishesAndInactiveUsers() {
        // given
        Long dropId = insertDrop("한정판 스니커즈");
        Long active = insertUser("ACTIVE");
        Long canceledWish = insertUser("ACTIVE");
        Long suspended = insertUser("SUSPENDED");
        Long withdrawn = insertUser("WITHDRAWN");
        insertWish(active, dropId, true);
        insertWish(canceledWish, dropId, false);
        insertWish(suspended, dropId, true);
        insertWish(withdrawn, dropId, true);

        // when
        List<WishMailRecipientProjection> recipients =
                wishRepository.findSaleStartMailRecipients(List.of(dropId));

        // then
        assertThat(recipients).extracting(WishMailRecipientProjection::getEmail)
                .containsExactly(emailOf(active));
    }

    @Test
    @DisplayName("전환 트랜잭션이 커밋되면 활성 WISH 사용자에게 보낼 메일이 발송 창구로 넘어간다")
    void dispatchesMailAfterCommit() {
        // given
        Long dropId = insertDrop("한정판 스니커즈");
        Long userId = insertUser("ACTIVE");
        insertWish(userId, dropId, true);

        // when: 전환 배치가 하는 것과 같이 트랜잭션 안에서 이벤트를 발행하고 커밋한다
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> eventPublisher.publishEvent(new DropGrabStartedEvent(List.of(dropId))));

        // then
        ArgumentCaptor<List<EmailMessage>> captor = ArgumentCaptor.captor();
        verify(emailDispatcher).dispatchAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(message -> {
            assertThat(message.bcc()).containsExactly(emailOf(userId));
            assertThat(message.subject()).contains("한정판 스니커즈");
            assertThat(message.htmlBody()).contains("한정판 스니커즈", "/drops/" + dropId);
            assertThat(message.textBody()).contains("한정판 스니커즈", "/drops/" + dropId);
        });
    }

    @Test
    @DisplayName("전환 트랜잭션이 롤백되면 메일이 발송되지 않는다")
    void doesNotDispatchOnRollback() {
        // given
        Long dropId = insertDrop("한정판 스니커즈");
        Long userId = insertUser("ACTIVE");
        insertWish(userId, dropId, true);

        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new DropGrabStartedEvent(List.of(dropId)));
            status.setRollbackOnly();
        });

        // then
        verifyNoInteractions(emailDispatcher);
    }

    @Test
    @DisplayName("수신자가 없으면 발송 창구를 호출하지 않는다")
    void skipsDispatchWithoutRecipients() {
        // given
        Long dropId = insertDrop("WISH 없는 DROP");

        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> eventPublisher.publishEvent(new DropGrabStartedEvent(List.of(dropId))));

        // then
        verifyNoInteractions(emailDispatcher);
    }

    @Test
    @DisplayName("판매 임박 이벤트가 커밋되면 같은 수신자에게 임박 메일이 발송 창구로 넘어간다")
    void dispatchesStartingSoonMailAfterCommit() {
        // given
        Long dropId = insertDrop("한정판 스니커즈");
        Long userId = insertUser("ACTIVE");
        insertWish(userId, dropId, true);

        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> eventPublisher.publishEvent(new DropSaleStartingSoonEvent(List.of(dropId))));

        // then
        ArgumentCaptor<List<EmailMessage>> captor = ArgumentCaptor.captor();
        verify(emailDispatcher).dispatchAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(message -> {
            assertThat(message.bcc()).containsExactly(emailOf(userId));
            assertThat(message.subject()).isEqualTo("[GRAB] 한정판 스니커즈 판매가 곧 시작됩니다");
        });
    }

    private String emailPrefix() {
        return "gr69-" + sellerId + "-";
    }

    private String emailOf(Long userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private Long insertUser(String status) {
        String unique = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, status)
                VALUES (?, 'encoded-password', ?, ?)
                RETURNING id
                """,
                Long.class,
                emailPrefix() + unique + "@example.com",
                "회원-" + unique,
                status);
    }

    // 닉네임에도 유니크 제약(uq_users_nickname_lower)이 있어 판매자 계정 이름도 매번 다르게 만든다
    private Long insertSeller() {
        String unique = UUID.randomUUID().toString();
        sellerUserId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', ?)
                RETURNING id
                """,
                Long.class, "gr69-seller-" + unique + "@example.com", "판매자-" + unique);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?)
                RETURNING id
                """,
                Long.class, sellerUserId, "gr69-contact-" + unique + "@example.com");
    }

    // 전환 직후 상태를 흉내 내 GRAB으로 넣는다. grab_started_at은 CHECK 제약 때문에 함께 채운다.
    private Long insertDrop(String name) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                                   sale_starts_at, sale_ends_at, published_at, grab_started_at)
                VALUES (?, 1, 'GRAB', ?, '설명', 3000, '배송 안내',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP + INTERVAL '2 hours',
                        CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class, sellerId, name);
    }

    private void insertWish(Long userId, Long dropId, boolean active) {
        jdbcTemplate.update(
                """
                INSERT INTO wishes (user_id, drop_id, activated_at, canceled_at)
                VALUES (?, ?, CURRENT_TIMESTAMP - INTERVAL '1 hour',
                        CASE WHEN ? THEN NULL ELSE CURRENT_TIMESTAMP END)
                """,
                userId, dropId, active);
    }
}
