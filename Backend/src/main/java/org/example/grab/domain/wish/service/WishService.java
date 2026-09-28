package org.example.grab.domain.wish.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.domain.wish.WishNotice;
import org.example.grab.domain.wish.dto.response.WishResponse;
import org.example.grab.domain.wish.entity.Wish;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * WISH 등록·취소 파사드. DROP 판정은 DropService에 맡기고, 저장은 WishTransactionService에 위임한다.
 * 시각은 요청당 한 번만 만들어 검증·저장에 같은 값을 쓴다.
 */
@Service
@RequiredArgsConstructor
public class WishService {

    private final DropService dropService;
    private final WishTransactionService wishTransactionService;

    /**
     * WISH를 등록한다. 이미 활성이면 기존 등록 시각을 그대로 반환하고(멱등),
     * 취소된 WISH면 기존 행을 재활성화한다.
     */
    public WishResponse register(Long userId, Long dropId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dropService.validateWishable(dropId, now);
        try {
            Wish wish = wishTransactionService.register(userId, dropId, now);
            return toResponse(dropId, wish);
        } catch (DataIntegrityViolationException exception) {
            return resolveConcurrentRequest(userId, dropId, exception);
        }
    }

    /** 활성 WISH가 없으면 아무 것도 하지 않는다(멱등 204). DROP 상태 검사가 먼저다. */
    public void cancel(Long userId, Long dropId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dropService.validateWishable(dropId, now);
        wishTransactionService.cancel(userId, dropId, now);
    }

    private WishResponse resolveConcurrentRequest(
            Long userId, Long dropId, DataIntegrityViolationException originalException) {
        return wishTransactionService.findActiveAfterConcurrentInsert(userId, dropId)
                .map(wish -> toResponse(dropId, wish))
                .orElseThrow(() -> originalException);
    }

    private WishResponse toResponse(Long dropId, Wish wish) {
        return new WishResponse(dropId, true, wish.getActivatedAt(), WishNotice.MESSAGE);
    }
}
