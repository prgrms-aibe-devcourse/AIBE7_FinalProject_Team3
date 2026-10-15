package org.example.grab.domain.drop.stream.service;

import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.domain.drop.stream.StockStreamTestSupport.MutableClock;
import org.example.grab.domain.drop.stream.model.StockStreamSubscription;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.grab.domain.drop.stream.StockStreamTestSupport.defaults;
import static org.example.grab.domain.drop.stream.StockStreamTestSupport.properties;
import static org.mockito.Mockito.*;

class StockStreamSubscriptionServiceTest {

    private final DropRepository repository = mock(DropRepository.class);
    private final StockStreamRegistry registry = new StockStreamRegistry(defaults(), new MutableClock());
    private final PublicDropSubscriptionService publicService = new PublicDropSubscriptionService(repository);
    private final StockStreamSubscriptionService service = new StockStreamSubscriptionService(
            new StockStreamQueryParser(defaults()), publicService, registry);

    @Test
    @DisplayName("정규화한 공개 상품만 구독 저장소에 등록한다")
    void registersNormalizedPublicDrops() {
        // given
        when(repository.countPublicDropsForSubscription(List.of(1L, 2L))).thenReturn(2L);

        // when
        StockStreamSubscription subscription = service.subscribe("127.0.0.1", List.of("2,01,2"));

        // then
        assertThat(subscription.dropIds()).containsExactly(1L, 2L);
        assertThat(registry.findSubscribers(1)).containsExactly(subscription);
        verify(repository).countPublicDropsForSubscription(List.of(1L, 2L));
    }

    @Test
    @DisplayName("없는 상품·비공개 혼합은 전체 404이며 연결 슬롯과 상품 인덱스를 남기지 않는다")
    void rejectsMixedPrivateOrMissingDropsWithoutLeakingIds() {
        // given
        when(repository.countPublicDropsForSubscription(List.of(1L, 2L))).thenReturn(1L);

        // when & then
        assertThatThrownBy(() -> service.subscribe("127.0.0.1", List.of("1,2")))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(DropErrorCode.DROP_NOT_FOUND);
                    assertThat(error.getMessage()).isEqualTo(DropErrorCode.DROP_NOT_FOUND.getMessage());
                    assertThat(error.getFieldErrors()).isEmpty();
                });
        assertThat(registry.countConnections()).isZero();
        assertThat(registry.findSubscribers(1)).isEmpty();
    }

    @Test
    @DisplayName("쿼리 오류와 공개 검증 실패도 요청 제한에 포함하며 연결 슬롯은 점유하지 않는다")
    void countsFailedAttemptsWithoutAllocatingConnections() {
        // given
        StockStreamRegistry limited = new StockStreamRegistry(properties(200, 20, 2, 100), new MutableClock());
        StockStreamSubscriptionService target = new StockStreamSubscriptionService(
                new StockStreamQueryParser(defaults()), publicService, limited);

        // when & then
        assertThatThrownBy(() -> target.subscribe("127.0.0.1", List.of("bad"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> target.subscribe("127.0.0.1", List.of("1"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> target.subscribe("127.0.0.1", List.of("1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(DropErrorCode.STOCK_STREAM_LIMIT_EXCEEDED));
        assertThat(limited.countConnections()).isZero();
        verify(repository, times(1)).countPublicDropsForSubscription(List.of(1L));
    }
}
