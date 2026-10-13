package org.example.grab.domain.seller.gateway;

// 사업자등록번호 1차 검증(SELLER-005). 국세청 상태조회 API 연동(GR-90)이 이 인터페이스로 구현된다.
public interface BusinessRegistrationVerifier {

    BusinessRegistrationStatus verify(String businessRegistrationNumber);
}
