package org.example.grab.domain.seller.gateway;

public enum BusinessRegistrationStatus {
    ACTIVE, // 계속사업자
    INACTIVE, // 휴업자·폐업자, 또는 등록되지 않은 사업자등록번호
    UNAVAILABLE // 외부 API 타임아웃·오류로 조회하지 못함
}
