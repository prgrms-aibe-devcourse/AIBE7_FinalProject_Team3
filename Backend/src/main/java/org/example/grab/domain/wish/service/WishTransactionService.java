package org.example.grab.domain.wish.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.domain.wish.entity.Wish;
import org.example.grab.domain.wish.repository.WishRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * WISH 등록·취소의 트랜잭션 경계. 동시 첫 등록의 유니크 제약 위반을 파사드(WishService)가
 * 트랜잭션 밖에서 잡아 재조회할 수 있도록, 저장과 재조회를 각각 별도 트랜잭션으로 분리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishTransactionService {

    private final WishRepository wishRepository;
    private final DropService dropService;

    /**
     * 없으면 새로 만들고, 취소 상태면 재활성화한다. 활성이면 기존 행을 그대로 반환한다(멱등).
     * saveAndFlush로 INSERT를 즉시 실행해 유니크 제약 위반을 이 메서드 안에서 터뜨린다.
     */
    @Transactional
    public Wish register(Long userId, Long dropId) {
        Optional<Wish> existing = wishRepository.findByUserIdAndDropIdForUpdate(userId, dropId);
        OffsetDateTime now = now();
        dropService.validateWishable(dropId, now);
        Wish wish = existing
                .map(found -> reactivateIfCanceled(found, now))
                .orElseGet(() -> Wish.activate(userId, dropId, now));
        return wishRepository.saveAndFlush(wish);
    }

    @Transactional
    public void cancel(Long userId, Long dropId) {
        Optional<Wish> existing = wishRepository.findByUserIdAndDropIdForUpdate(userId, dropId);
        OffsetDateTime now = now();
        dropService.validateWishable(dropId, now);
        existing
                .filter(Wish::isActive)
                // 서버 시계가 뒤로 조정되더라도 DB의 canceled_at >= activated_at 제약을 지킨다.
                .ifPresent(wish -> wish.cancel(now.isBefore(wish.getActivatedAt()) ? wish.getActivatedAt() : now));
    }

    /** 잠금 대기 이후의 시각으로 판정하고, DB 저장 정밀도에 맞춰 응답 시각을 일치시킨다. */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 동시 요청 중 다른 트랜잭션이 먼저 저장한 활성 WISH를 새 트랜잭션에서 조회한다.
     * 호출자는 유니크 제약 위반으로 기존 트랜잭션이 종료된 뒤 호출해야 한다.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<Wish> findActiveAfterConcurrentInsert(Long userId, Long dropId) {
        return wishRepository.findByUserIdAndDropId(userId, dropId)
                .filter(Wish::isActive);
    }

    private Wish reactivateIfCanceled(Wish wish, OffsetDateTime now) {
        if (!wish.isActive()) {
            wish.reactivate(now);
        }
        return wish;
    }
}
