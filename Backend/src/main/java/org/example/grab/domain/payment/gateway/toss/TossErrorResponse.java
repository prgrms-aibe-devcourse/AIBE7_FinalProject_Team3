package org.example.grab.domain.payment.gateway.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 토스페이먼츠 오류 응답 형식
@JsonIgnoreProperties(ignoreUnknown = true)
record TossErrorResponse(String code, String message) {
}
