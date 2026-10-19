package org.example.grab.domain.drop.stream.controller;

import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.stream.service.StockStreamSubscriptionService;
import org.example.grab.domain.drop.stream.transport.StockStreamHub;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.example.grab.domain.drop.stream.StockStreamTestSupport.defaults;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockStreamControllerTest {

    private final StockStreamSubscriptionService subscriptions = mock(StockStreamSubscriptionService.class);
    private final StockStreamHub hub = mock(StockStreamHub.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new StockStreamController(subscriptions, hub, defaults()))
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .build();
    }

    @Test
    @DisplayName("SSE를 요청했어도 연결 전 오류는 공통 JSON으로 응답한다")
    void returnsJsonForInvalidRequest() throws Exception {
        // given
        when(subscriptions.subscribe(anyString(), any())).thenThrow(new BusinessException(CommonErrorCode.INVALID_REQUEST));
        // when & then
        mvc.perform(get("/api/v1/drops/stock-stream").param("dropIds", "bad").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("기반 준비 실패는 JSON 503과 초 단위 Retry-After다")
    void returnsRetryAfterForUnavailable() throws Exception {
        // given
        when(subscriptions.subscribe(anyString(), any())).thenThrow(new BusinessException(DropErrorCode.STOCK_STREAM_UNAVAILABLE, 60));
        // when & then
        mvc.perform(get("/api/v1/drops/stock-stream").param("dropIds", "1").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "60"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("STOCK_STREAM_UNAVAILABLE"));
    }

    @Test
    @DisplayName("예상하지 못한 연결 전 장애도 JSON 500이며 내부 메시지를 노출하지 않는다")
    void returnsJsonForUnexpectedFailure() throws Exception {
        // given
        when(subscriptions.subscribe(anyString(), any())).thenThrow(new IllegalStateException("내부 비공개 오류"));
        // when & then
        mvc.perform(get("/api/v1/drops/stock-stream").param("dropIds", "1").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isInternalServerError()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.message").value(CommonErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }
}
