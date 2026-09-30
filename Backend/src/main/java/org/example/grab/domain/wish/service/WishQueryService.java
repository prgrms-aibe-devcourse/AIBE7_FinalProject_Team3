package org.example.grab.domain.wish.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.wish.repository.WishRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 도메인이 활성 WISH 수를 조회하는 읽기 전용 창구.
 * DropService가 WishRepository를 직접 참조하지 않도록(CODING_CONVENTION 2.1) 두고,
 * DropService에 WishService를 주입하면 순환 의존이 생기므로 별도 조회 서비스로 분리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishQueryService {

    private final WishRepository wishRepository;

    public long countActiveByDropId(Long dropId) {
        return wishRepository.countByDropIdAndCanceledAtIsNull(dropId);
    }
}
