package org.example.grab.domain.seller.gateway.nts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// 국세청 사업자등록 상태조회(공공데이터포털 nts-businessman v1/status) 응답 중 상태 판단에 필요한 필드만 읽는다.
@JsonIgnoreProperties(ignoreUnknown = true)
record NtsBusinessStatusResponse(
        @JsonProperty("status_code") String statusCode,
        @JsonProperty("match_cnt") Integer matchCnt,
        List<Item> data
) {

    static final String OK_STATUS_CODE = "OK";

    // b_stt_cd: 01 계속사업자, 02 휴업자, 03 폐업자. 등록되지 않은 번호는 빈 값이다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(
            @JsonProperty("b_no") String businessRegistrationNumber,
            @JsonProperty("b_stt_cd") String statusCode
    ) {
        static final String ACTIVE_CODE = "01";
    }
}
