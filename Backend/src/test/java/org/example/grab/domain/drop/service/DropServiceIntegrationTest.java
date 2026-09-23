package org.example.grab.domain.drop.service;

import jakarta.persistence.EntityManager;
import org.example.grab.config.JpaConfig;
import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.request.OptionGroupRequest;
import org.example.grab.domain.drop.dto.request.OptionRequest;
import org.example.grab.domain.drop.dto.request.OptionValueRequest;
import org.example.grab.domain.drop.dto.request.SelectionRequest;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({JpaConfig.class, DropService.class})
@Testcontainers
@Sql("/sql/drop-fixtures.sql")
class DropServiceIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DropService dropService;

    @Autowired
    private DropRepository dropRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("필수 항목이 비어 있어도 DRAFT로 저장된다")
    void createDraft_persistsDraft() {
        // given
        DropDraftRequest request = emptyRequest();

        // when
        Drop saved = dropService.createDraft(1L, request);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(DropStatus.DRAFT);

        entityManager.clear();
        Drop found = dropRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(DropStatus.DRAFT);
    }

    @Test
    @DisplayName("옵션 조합과 이미지·옵션 그룹·값 정렬 순서가 요청대로 저장된다")
    void createDraft_persistsOptionsWithSortOrder() {
        // when
        Drop saved = dropService.createDraft(1L, fullRequest());

        // then
        entityManager.clear();
        Drop found = dropRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getImages()).extracting(DropImage::getSortOrder).containsExactly(0, 1);
        assertThat(found.getOptionGroups()).hasSize(2);
        assertThat(found.getOptionGroups().get(0).getValues()).hasSize(2);
        assertThat(found.getOptionGroups().get(0).getValues().get(0).getValue()).isEqualTo("코튼");
        assertThat(found.getOptions()).hasSize(2);
        assertThat(found.getOptions().get(0).getValueMaps()).hasSize(2);
        assertThat(found.getOptions().get(0).getAvailableQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("DRAFT 수정 내용이 저장된다")
    void updateDraft_persistsChanges() {
        // given
        Drop saved = dropService.createDraft(1L, fullRequest());
        entityManager.flush();
        entityManager.clear();

        // when
        dropService.updateDraft(1L, saved.getId(),
                new DropDraftRequest("수정된 이름", null, null, null, null, null, null, null, null));
        entityManager.flush();
        entityManager.clear();

        // then
        Drop found = dropRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("수정된 이름");
        assertThat(found.getOptions()).hasSize(2);
    }

    @Test
    @DisplayName("일정 한쪽만 바꿔 시작이 종료 이후가 되면 INVALID_SCHEDULE")
    void updateDraft_rejectsInvalidScheduleAfterMerge() {
        // given
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        OffsetDateTime end = start.plusHours(2);
        Drop saved = dropService.createDraft(1L,
                new DropDraftRequest(null, null, null, null, start, end, null, null, null));
        entityManager.flush();

        // when & then
        assertThatThrownBy(() -> dropService.updateDraft(1L, saved.getId(),
                new DropDraftRequest(null, null, null, null, end.plusHours(1), null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.INVALID_SCHEDULE);
    }

    @Test
    @DisplayName("수정 시 이미지·옵션을 전체 교체해도 제약 위반 없이 저장된다")
    void updateDraft_replacesChildren() {
        // given
        Drop saved = dropService.createDraft(1L, fullRequest());
        entityManager.flush();
        entityManager.clear();

        OptionGroupRequest color = new OptionGroupRequest("color", "색상", 0,
                List.of(new OptionValueRequest("black", "블랙", 0)));
        OptionRequest option = new OptionRequest(
                List.of(new SelectionRequest("color", "black")), 5000L, 3, true, 0);
        DropDraftRequest update = new DropDraftRequest(null, null,
                List.of("https://example.com/c.jpg", "https://example.com/d.jpg"),
                null, null, null, null, List.of(color), List.of(option));

        // when
        dropService.updateDraft(1L, saved.getId(), update);
        entityManager.flush();
        entityManager.clear();

        // then
        Drop found = dropRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getImages()).extracting(DropImage::getImageUrl)
                .containsExactly("https://example.com/c.jpg", "https://example.com/d.jpg");
        assertThat(found.getOptionGroups()).singleElement()
                .extracting("name").isEqualTo("색상");
        assertThat(found.getOptions()).hasSize(1);
    }

    private DropDraftRequest fullRequest() {
        OptionGroupRequest material = new OptionGroupRequest("material", "소재", 0,
                List.of(new OptionValueRequest("cotton", "코튼", 0),
                        new OptionValueRequest("linen", "린넨", 1)));
        OptionGroupRequest length = new OptionGroupRequest("length", "길이", 1,
                List.of(new OptionValueRequest("short", "숏", 0),
                        new OptionValueRequest("long", "롱", 1)));

        OptionRequest first = new OptionRequest(
                List.of(new SelectionRequest("material", "cotton"), new SelectionRequest("length", "short")),
                129000L, 10, true, 0);
        OptionRequest second = new OptionRequest(
                List.of(new SelectionRequest("material", "linen"), new SelectionRequest("length", "long")),
                139000L, 5, false, 1);

        return new DropDraftRequest("상품", "설명",
                List.of("https://example.com/a.jpg", "https://example.com/b.jpg"),
                1L, null, null, null, List.of(material, length), List.of(first, second));
    }

    private DropDraftRequest emptyRequest() {
        return new DropDraftRequest(null, null, null, null, null, null, null, null, null);
    }
}
