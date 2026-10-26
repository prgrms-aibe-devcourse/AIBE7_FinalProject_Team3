package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.user.event.ProfileImageDiscardedEvent;
import org.example.grab.global.storage.ProfileImageStorage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
    교체·삭제한 이전 프로필 이미지 객체를 등록·삭제 트랜잭션이 커밋된 뒤 지운다(GR-130 M00-04).
    커밋 전에 지우면 롤백 시 DB가 없는 객체를 가리키므로 AFTER_COMMIT에서만 처리한다. 롤백되면 호출되지 않는다.
    삭제 실패(설정 없음·타임아웃·4xx·5xx)는 요청 결과를 바꾸지 않는다. 이미 커밋됐으므로 WARN만 남기고, 남은 객체는 고아 객체 정리(F06) 대상이다.
    요청 스레드에서 동기로 호출하므로 Supabase 응답 시간(타임아웃 상한)만큼 응답이 늦어질 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileImageCleanupListener {

    private final ProfileImageStorage profileImageStorage;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProfileImageDiscarded(ProfileImageDiscardedEvent event) {
        try {
            profileImageStorage.delete(event.objectKey());
        } catch (RuntimeException e) {
            // 공개 URL·객체 키·Secret Key는 남기지 않고 내부 ID와 사유만 남긴다
            log.warn("이전 프로필 이미지 삭제 실패: userId={}, cause={}", event.userId(), e.getClass().getSimpleName());
        }
    }
}
