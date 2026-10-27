package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.dto.DemandPlanDecisionRequest;
import org.example.grab.domain.demand.dto.DemandPlanDecisionRequest.DecidedQuantity;
import org.example.grab.domain.demand.dto.DemandPlanResponse;
import org.example.grab.domain.demand.entity.DemandPlanStatus;
import org.example.grab.domain.demand.entity.ExplanationStatus;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.global.error.BusinessException;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 ObjectMapper·트랜잭션으로 저장한 스냅샷을 다시 읽고 결정까지 기록되는지 확인한다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DemandPlanServiceIntegrationTest {

    @Autowired
    private DemandPlanService demandPlanService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("저장한 계획을 다시 조회하면 저장 시점 분석이 그대로 나오고, 결정은 한 번만 기록되며 재고를 바꾸지 않는다")
    void saveFindDecide() {
        // given: 판매 시작 전 WISH DROP, 옵션 2개(30·10개), 활성 WISH 1명
        Long userId = insertUser();
        Long sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class, userId, "contact-" + UUID.randomUUID() + "@example.com");
        Long categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '테스트') RETURNING id",
                Long.class, "TEST-" + UUID.randomUUID());
        Long dropId = jdbcTemplate.queryForObject("""
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                                   sale_starts_at, sale_ends_at, published_at)
                VALUES (?, ?, 'WISH', '상품', '설명', 0, '안내', CURRENT_TIMESTAMP + INTERVAL '1 day',
                        CURRENT_TIMESTAMP + INTERVAL '2 day', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, sellerId, categoryId);
        Long firstOption = insertOption(dropId, 30, 0);
        Long secondOption = insertOption(dropId, 10, 1);
        jdbcTemplate.update("INSERT INTO wishes (user_id, drop_id, activated_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                insertUser(), dropId);

        // when: 판매자가 전환율을 직접 넣어 저장
        DemandPlanResponse saved = demandPlanService.save(sellerId, userId, dropId,
                new DemandCalculateRequest(null, new BigDecimal("0.50"), null));
        DemandPlanResponse found = demandPlanService.findPlan(sellerId, saved.planId());

        // then: 스냅샷이 BigDecimal 자릿수·시각까지 그대로 돌아오고 설명은 요청하지 않은 상태다
        assertThat(found.analysis()).isEqualTo(saved.analysis());
        assertThat(found.analysis().assumptions().conversionRate().value()).isEqualByComparingTo("0.50");
        assertThat(found.explanationStatus()).isEqualTo(ExplanationStatus.DISABLED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT conversion_basis FROM demand_plans WHERE public_id = ?", String.class, saved.planId()))
                .isEqualTo("SELLER_INPUT");
        assertThat(demandPlanService.findPlans(sellerId, dropId, 0, 20).content()).hasSize(1);

        // when: 수량을 바꿔 결정
        List<DecidedQuantity> quantities = List.of(new DecidedQuantity(firstOption, 5), new DecidedQuantity(secondOption, 2));
        DemandPlanResponse decided = demandPlanService.decide(sellerId, saved.planId(),
                new DemandPlanDecisionRequest(DemandPlanStatus.MODIFIED, quantities, "보수적으로"));

        // then
        assertThat(decided.decision().quantities()).isEqualTo(quantities);
        assertThat(demandPlanService.findPlan(sellerId, saved.planId()).decision().status())
                .isEqualTo(DemandPlanStatus.MODIFIED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT SUM(total_quantity) FROM drop_options WHERE drop_id = ?", Integer.class, dropId))
                .isEqualTo(40);
        assertThatThrownBy(() -> demandPlanService.decide(sellerId, saved.planId(),
                new DemandPlanDecisionRequest(DemandPlanStatus.ACCEPTED, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.DEMAND_PLAN_ALREADY_DECIDED);
    }

    private Long insertUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class, "user-" + suffix + "@example.com", "회원-" + suffix);
    }

    private Long insertOption(Long dropId, int total, int sortOrder) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity, sold_quantity,
                                          withheld_quantity, is_active, sort_order)
                VALUES (?, 1000, ?, 0, 0, 0, TRUE, ?)
                RETURNING id
                """, Long.class, dropId, total, sortOrder);
    }
}
