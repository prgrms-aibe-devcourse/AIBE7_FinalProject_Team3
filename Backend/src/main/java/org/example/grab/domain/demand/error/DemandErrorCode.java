package org.example.grab.domain.demand.error;

import org.example.grab.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum DemandErrorCode implements ErrorCode {

    DEMAND_ANALYSIS_UNAVAILABLE(HttpStatus.CONFLICT, "수요 분석을 할 수 없는 상태의 DROP입니다.");

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
