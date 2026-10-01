package org.example.grab.global.security;

import java.util.Optional;
import java.util.UUID;

/*
    회원 public_id로 승인된 판매자의 내부 ID(sellers.id)를 찾는다(M00-03).
    JWT의 SELLER는 발급 시점 값이라 최대 15분 옛 값일 수 있으므로, 판매자 API의 최종 판단은 이 조회가 한다.
    global은 domain을 참조하지 않으므로 인터페이스만 두고 구현은 seller 도메인에 둔다.
 */
public interface SellerIdResolver {

    // status = APPROVED인 판매자만 돌려준다. PENDING·REJECTED·판매자가 아닌 회원은 빈 결과
    Optional<Long> resolveApproved(UUID userPublicId);
}
