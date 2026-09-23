package org.example.grab.domain.order.exception;

import org.example.grab.domain.order.controller.SellerOrderController;
import org.example.grab.global.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestControllerAdvice(assignableTypes = SellerOrderController.class)
public class SellerOrderExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleRequestException(ResponseStatusException exception) {
        String code = exception.getReason() == null ? "INTERNAL_ERROR" : exception.getReason();
        String message = switch (code) {
            case "ACCESS_DENIED" -> "판매자 주문을 조회할 권한이 없습니다.";
            case "RESOURCE_NOT_FOUND" -> "DROP을 찾을 수 없습니다.";
            default -> "요청 값이 올바르지 않습니다.";
        };
        return ResponseEntity.status(exception.getStatusCode())
                .body(ErrorResponse.of(code, message, List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParameter(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_REQUEST", "요청 값이 올바르지 않습니다.", List.of()));
    }
}
