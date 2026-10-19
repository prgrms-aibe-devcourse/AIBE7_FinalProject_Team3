package org.example.grab.domain.drop.stream.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.stream.model.StockStreamSubscription;
import org.example.grab.domain.drop.stream.config.StockStreamProperties;
import org.example.grab.domain.drop.stream.service.StockStreamSubscriptionService;
import org.example.grab.domain.drop.stream.transport.StockStreamAsyncInterceptor;
import org.example.grab.domain.drop.stream.transport.StockStreamHub;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.common.ErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;

@RestController
@RequiredArgsConstructor
@Tag(name = "공개 DROP", description = "로그인 없이 호출하는 DROP 목록·상세·재고 조회")
public class StockStreamController {

    private final StockStreamSubscriptionService subscriptions;
    private final StockStreamHub hub;
    private final StockStreamProperties properties;

    @Operation(summary = "공개 DROP 변경 구독", description = "최대 100개 입력 ID의 재고·판매량·상태 변경 신호를 구독한다. "
            + "ready 후 기존 API 재조회가 필요하며 replay는 지원하지 않는다. 상세는 REALTIME_STOCK.md 6~9절 참고")
    @Parameter(name = "dropIds", required = true, description = "쉼표 구분 양의 정수. 중복 쿼리 파라미터 불가",
            schema = @Schema(type = "string", example = "100,101"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "지속 스트림: ready, drop-changed, stream-closed",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "INVALID_TOKEN",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DROP_NOT_FOUND",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "STOCK_STREAM_LIMIT_EXCEEDED",
            headers = @Header(name = "Retry-After", description = "재시도 대기 초", schema = @Schema(type = "integer", format = "int64")),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "STOCK_STREAM_UNAVAILABLE",
            headers = @Header(name = "Retry-After", description = "재시도 대기 초", schema = @Schema(type = "integer", format = "int64")),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping(value = "/api/v1/drops/stock-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(HttpServletRequest request) {
        if (!request.isAsyncSupported()) {
            throw new BusinessException(DropErrorCode.STOCK_STREAM_UNAVAILABLE,
                    properties.retryAfter().getSeconds() + (properties.retryAfter().getNano() > 0 ? 1 : 0));
        }
        String[] values = request.getParameterValues("dropIds");
        StockStreamSubscription subscription = subscriptions.subscribe(
                request.getRemoteAddr(), values == null ? null : Arrays.asList(values));
        SseEmitter emitter = hub.open(subscription);
        request.setAttribute(StockStreamAsyncInterceptor.SUBSCRIPTION_ATTRIBUTE, subscription.id());
        return ResponseEntity.ok().contentType(new MediaType("text", "event-stream", java.nio.charset.StandardCharsets.UTF_8))
                .header("Cache-Control", "no-cache, no-store").body(emitter);
    }
}
