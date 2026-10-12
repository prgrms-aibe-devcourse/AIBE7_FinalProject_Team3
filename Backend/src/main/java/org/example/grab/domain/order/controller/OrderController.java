package org.example.grab.domain.order.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.MyOrderDetailResponse;
import org.example.grab.domain.order.dto.MyOrderListResponse;
import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.dto.OrderCreateResponse;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.OrderCreateService;
import org.example.grab.domain.order.service.OrderQueryService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.common.PublicIdParser;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
@Tag(name = "주문", description = "구매자의 주문 생성과 내 주문 조회")
public class OrderController {

    private final OrderCreateService orderCreateService;
    private final OrderQueryService orderQueryService;
    private final CurrentUserIdProvider currentUserIdProvider;

    @Operation(summary = "주문 생성",
            description = "옵션별 재고를 확보하고 PAYMENT_PENDING 주문을 만든다. Idempotency-Key(UUID) 헤더가 필요하다. 가격은 서버 값으로 계산한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "주문 생성 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, INVALID_IDEMPOTENCY_KEY, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED (CSRF 토큰 누락)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "OPTION_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DROP_NOT_ON_SALE, SALE_NOT_STARTED, SALE_ENDED, DUPLICATE_IDEMPOTENCY_KEY")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "INSUFFICIENT_STOCK")
    @PostMapping
    public ResponseEntity<ApiResponse<OrderCreateResponse>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest request
    ) {
        OrderCreateResponse response = orderCreateService.create(
                currentUserIdProvider.currentUserId(),
                idempotencyKey,
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @Operation(summary = "내 주문 목록 조회", description = "본인 주문을 최신순으로 반환한다. 주문 상태로 거를 수 있다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @GetMapping
    public ApiResponse<PageResponse<MyOrderListResponse>> findMyOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(orderQueryService.findMyOrders(
                currentUserIdProvider.currentUserId(), status, page, size));
    }

    @Operation(summary = "내 주문 상세 조회",
            description = "주문·결제·배송 정보를 반환한다. 다른 사용자의 주문은 없는 주문과 같이 ORDER_NOT_FOUND로 응답한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND (UUID 형식 오류), ORDER_NOT_FOUND")
    @GetMapping("/{orderId}")
    public ApiResponse<MyOrderDetailResponse> findMyOrder(@PathVariable String orderId) {
        return ApiResponse.success(orderQueryService.findMyOrder(
                currentUserIdProvider.currentUserId(), PublicIdParser.parse(orderId)));
    }
}
