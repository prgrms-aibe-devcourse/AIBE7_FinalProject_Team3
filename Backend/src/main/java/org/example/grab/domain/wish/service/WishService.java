package org.example.grab.domain.wish.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.wish.WishNotice;
import org.example.grab.domain.wish.dto.response.WishResponse;
import org.example.grab.domain.wish.entity.Wish;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * WISH 등록·취소 파사드. DROP 판정과 저장은 WishTransactionService의 트랜잭션에서 처리한다.
 * 트랜잭션 경계는 WishTransactionService가 가지며 이 클래스에는 트랜잭션을 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class WishService {

    private final WishTransactionService wishTransactionService;

    /**
     * WISH를 등록한다. 이미 활성이면 기존 등록 시각을 그대로 반환하고(멱등),
     * 취소된 WISH면 기존 행을 재활성화한다.
     */
    public WishResponse register(Long userId, Long dropId) {
        try {
            Wish wish = wishTransactionService.register(userId, dropId);
            return toResponse(dropId, wish);
        } catch (DataIntegrityViolationException ignored) {
            return resolveConcurrentRequest(userId, dropId);
        }
    }

    /** 활성 WISH가 없으면 아무 것도 하지 않는다(멱등 204). */
    public void cancel(Long userId, Long dropId) {
        wishTransactionService.cancel(userId, dropId);
    }

    /**
     * 유니크 제약 위반은 동시 등록 경합에서만 발생한다. 새 트랜잭션에서 활성 WISH를 찾으면 그 결과를 그대로 응답하고,
     * 찾지 못하면(경합 상대가 그 사이 취소된 경우 등) 공통 오류 응답으로 변환될 수 있도록 도메인 예외를 던진다.
     */
    private WishResponse resolveConcurrentRequest(Long userId, Long dropId) {
        return wishTransactionService.findActiveAfterConcurrentInsert(userId, dropId)
                .map(wish -> toResponse(dropId, wish))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION));
    }

    private WishResponse toResponse(Long dropId, Wish wish) {
        return new WishResponse(dropId, true, wish.getActivatedAt(), WishNotice.MESSAGE);
    }
}
