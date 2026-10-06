package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.request.DropImageRequest;
import org.example.grab.domain.drop.dto.request.OptionGroupRequest;
import org.example.grab.domain.drop.dto.request.OptionRequest;
import org.example.grab.domain.drop.dto.request.OptionValueRequest;
import org.example.grab.domain.drop.dto.request.SelectionRequest;
import org.example.grab.domain.drop.dto.request.ShippingRequest;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DRAFT 수정·공개가 DROP 행 잠금으로 직렬화되어, 공개 검증을 통과하지 못한 데이터가 공개되지 않고
 * 공개가 먼저 끝나면 이후 수정이 거부되는지 실제 PostgreSQL 잠금 아래에서 확인한다(GR-64 R03).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DropDraftPublishConcurrencyIntegrationTest {

    private static final String IMAGE_URL_PREFIX =
            "https://project.supabase.co/storage/v1/object/public/drop-images/images/";

    @Autowired
    private DropService dropService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private Long userId;
    private Long sellerId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        String unique = UUID.randomUUID().toString();
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded-password', ?) RETURNING id",
                Long.class, unique + "@example.com", "판매자-" + unique);
        sellerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?) RETURNING id
                """,
                Long.class, userId, "seller-" + unique + "@example.com");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '패션') RETURNING id",
                Long.class, "TEST-" + unique);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        // @SpringBootTest는 롤백하지 않으므로 이 테스트가 만든 DROP·자식과 부모 행을 FK 순서대로 정리한다.
        deleteDropsForSeller();
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
    }

    private void deleteDropsForSeller() {
        jdbcTemplate.update(
                "DELETE FROM drop_option_value_maps WHERE option_id IN (SELECT id FROM drop_options WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?))",
                sellerId);
        jdbcTemplate.update(
                "DELETE FROM drop_options WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?)", sellerId);
        jdbcTemplate.update(
                "DELETE FROM drop_images WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?)", sellerId);
        jdbcTemplate.update(
                "DELETE FROM drop_option_values WHERE group_id IN (SELECT id FROM drop_option_groups WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?))",
                sellerId);
        jdbcTemplate.update(
                "DELETE FROM drop_option_groups WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?)", sellerId);
        jdbcTemplate.update("DELETE FROM drops WHERE seller_id = ?", sellerId);
    }

    @Test
    @DisplayName("수정이 행을 잠근 채 이미지·옵션을 비우면, 기다리던 공개는 공개 검증 실패로 롤백되고 DRAFT로 남는다")
    void publishWaitsForEditThenFailsValidation() throws Exception {
        // given: 자식이 있는 DRAFT
        Long dropId = createDraftWithChildren();
        int baseline = waitingForLock();

        // T1이 행을 잠그고 자식을 비운 상태에서 커밋 직전까지 붙잡는다
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> edit = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    jdbcTemplate.queryForObject("SELECT id FROM drops WHERE id = ? FOR UPDATE", Long.class, dropId);
                    jdbcTemplate.update(
                            "DELETE FROM drop_option_value_maps WHERE option_id IN (SELECT id FROM drop_options WHERE drop_id = ?)",
                            dropId);
                    jdbcTemplate.update("DELETE FROM drop_options WHERE drop_id = ?", dropId);
                    jdbcTemplate.update("DELETE FROM drop_images WHERE drop_id = ?", dropId);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // when: 공개가 잠금을 기다린다
        Future<Void> publish = executor.submit(() -> {
            dropService.publish(sellerId, dropId);
            return null;
        });
        awaitWaitingForLock(baseline + 1);
        assertThat(publish.isDone()).isFalse();

        release.countDown();
        edit.get(10, TimeUnit.SECONDS);

        // then: 잠금 해제 후 공개는 자식이 없는 상태를 검증해 실패하고 DRAFT로 남는다
        assertThatThrownBy(() -> publish.get(10, TimeUnit.SECONDS))
                .cause()
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        assertThat(statusOf(dropId)).isEqualTo(DropStatus.DRAFT);
    }

    @Test
    @DisplayName("공개가 먼저 커밋하면 기다리던 수정은 DROP_NOT_EDITABLE로 거부되고 값은 공개 시점 그대로다")
    void editWaitsForPublishThenRejected() throws Exception {
        // given
        Long dropId = createDraftWithChildren();
        int baseline = waitingForLock();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> publish = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    jdbcTemplate.queryForObject("SELECT id FROM drops WHERE id = ? FOR UPDATE", Long.class, dropId);
                    jdbcTemplate.update("UPDATE drops SET status = 'WISH', published_at = CURRENT_TIMESTAMP WHERE id = ?",
                            dropId);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // when: 수정이 잠금을 기다린다
        Future<?> edit = executor.submit(() -> dropService.updateDraft(sellerId, dropId, nameOnlyRequest("새 이름")));
        awaitWaitingForLock(baseline + 1);

        release.countDown();
        publish.get(10, TimeUnit.SECONDS);

        // then
        assertThatThrownBy(() -> edit.get(10, TimeUnit.SECONDS))
                .cause()
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_EDITABLE);
        assertThat(originalName(dropId)).isEqualTo("상품");
        assertThat(statusOf(dropId)).isEqualTo(DropStatus.WISH);
    }

    private Long createDraftWithChildren() {
        Long dropId = dropService.createDraft(sellerId, fullRequest()).getId();
        jdbcTemplate.update("UPDATE drops SET status = 'DRAFT' WHERE id = ?", dropId);
        return dropId;
    }

    private DropDraftRequest fullRequest() {
        OptionGroupRequest group = new OptionGroupRequest("color", "색상", 0,
                List.of(new OptionValueRequest("black", "블랙", 0)));
        OptionRequest option = new OptionRequest(
                List.of(new SelectionRequest("color", "black")), 5000L, 5, true, 0);
        UUID imageId = UUID.randomUUID();
        return new DropDraftRequest("상품", "설명",
                List.of(new DropImageRequest(imageId, IMAGE_URL_PREFIX + imageId + ".jpg")), categoryId,
                java.time.OffsetDateTime.now().plusDays(1), java.time.OffsetDateTime.now().plusDays(2),
                new ShippingRequest(3000L, "안내"), List.of(group), List.of(option));
    }

    private DropDraftRequest nameOnlyRequest(String name) {
        return new DropDraftRequest(name, null, null, null, null, null, null, null, null);
    }

    private DropStatus statusOf(Long dropId) {
        return jdbcTemplate.queryForObject("SELECT status FROM drops WHERE id = ?", DropStatus.class, dropId);
    }

    private String originalName(Long dropId) {
        return jdbcTemplate.queryForObject("SELECT name FROM drops WHERE id = ?", String.class, dropId);
    }

    private int waitingForLock() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                Integer.class);
        return count == null ? 0 : count;
    }

    private void awaitWaitingForLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (waitingForLock() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("행 잠금 대기에 들어간 트랜잭션이 " + expected + "개가 되지 않았습니다.");
            }
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
