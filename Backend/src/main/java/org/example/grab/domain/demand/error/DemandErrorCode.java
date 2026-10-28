package org.example.grab.domain.demand.error;

import org.example.grab.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum DemandErrorCode implements ErrorCode {

    DEMAND_ANALYSIS_UNAVAILABLE(HttpStatus.CONFLICT, "수요 분석을 할 수 없는 상태의 DROP입니다."),
    DEMAND_EXPLANATION_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘 요청할 수 있는 수요 분석 설명 횟수를 넘었습니다.");

    private final HttpStatus status;
    private final String message;

    DemandErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() {
        return status;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getMessage() {
        return message;
    }
}
