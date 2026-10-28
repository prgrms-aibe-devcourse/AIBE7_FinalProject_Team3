package org.example.grab.domain.user.event;

/**
 * 프로필 이미지를 교체·삭제해 이전 객체가 더 이상 쓰이지 않게 됐을 때 발행한다(GR-130 M00-04).
 * 등록·삭제 트랜잭션 안에서 발행하고, 수신 측은 AFTER_COMMIT에서 객체를 지워 롤백된 요청에는 반응하지 않는다.
 * objectKey는 본인 발급 URL 검증(ProfileImageUrlVerifier)을 통과한 profile-images 버킷 안 경로다.
 */
public record ProfileImageDiscardedEvent(Long userId, String objectKey) {
}
