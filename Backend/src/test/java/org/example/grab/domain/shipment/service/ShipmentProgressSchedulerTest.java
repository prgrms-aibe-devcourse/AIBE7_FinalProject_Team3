package org.example.grab.domain.shipment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ShipmentProgressSchedulerTest {

    @Mock
    private ShipmentProgressService shipmentProgressService;

    @InjectMocks
    private ShipmentProgressScheduler scheduler;

    @Test
    @DisplayName("UTC 현재 시각을 만들어 서비스에 그대로 전달한다")
    void passesUtcNowToService() {
        // given
        given(shipmentProgressService.progress(org.mockito.ArgumentMatchers.any())).willReturn(0L);

        // when
        scheduler.progress();

        // then
        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        then(shipmentProgressService).should().progress(captor.capture());
        assertThat(captor.getValue().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("진행 중 예외가 나도 밖으로 전파하지 않아 다음 실행이 이어진다")
    void swallowsException() {
        // given
        given(shipmentProgressService.progress(org.mockito.ArgumentMatchers.any()))
                .willThrow(new RuntimeException("일시적 DB 오류"));

        // when & then
        assertThatCode(scheduler::progress).doesNotThrowAnyException();
    }
}
