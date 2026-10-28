package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.user.dto.response.ProfileImageResponse;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.event.ProfileImageDiscardedEvent;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/*
    내 프로필 이미지 등록/수정·삭제(MEMBER_AUTH.md 1.12·1.13, GR-130). 사용자 ID는 토큰의 본인으로 정하고 경로·쿼리로 받지 않는다.
    판정 순서(M00-06)
    - 등록: 회원 잠금 조회·존재·ACTIVE(401) → 본인 발급 URL(VALIDATION_FAILED, imageUrl) → 저장
    - 삭제: 회원 잠금 조회·존재·ACTIVE(401) → null로 저장
    이전 객체(M00-04)는 이벤트로 넘겨 커밋 후 지운다(ProfileImageCleanupListener). 롤백되면 지우지 않는다.
    - 이전 값이 본인 발급 URL일 때만 지운다. 다른 회원·DROP 이미지·형식 밖 값은 남긴다(3번)
    - 같은 URL 재등록·이전 값 null이면 지울 것이 없다(4번)
    - 이전 값은 회원 행을 잠그고 읽는다. 같은 회원의 두 요청이 같은 이전 값을 보고 엇갈려, 다른 요청이 막 저장한 객체를 지우지 않게 한다(5번)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileImageCommandService {

    private final UserRepository userRepository;
    private final ProfileImageUrlVerifier profileImageUrlVerifier;
    private final ApplicationEventPublisher eventPublisher;

    // imageUrl은 요청 DTO에서 필수값 검증을 마친 값이다. 검증을 통과한 값을 가공하지 않고 저장한다(M00-03)
    @Transactional
    public ProfileImageResponse register(Long userId, String imageUrl) {
        User user = lockActiveUser(userId, "프로필 이미지 등록");
        if (profileImageUrlVerifier.ownedObjectKey(user.getUuid(), imageUrl).isEmpty()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, List.of(
                    new ErrorResponse.FieldError("imageUrl", "본인이 발급받은 프로필 이미지 URL이 아닙니다.")));
        }

        String previous = user.getProfileImageUrl();
        user.changeProfileImage(imageUrl);
        if (!Objects.equals(previous, imageUrl)) {
            discard(user, previous);
        }
        return ProfileImageResponse.from(user);
    }

    // 이미지가 없어도 204로 끝난다(M00-06 멱등 삭제)
    @Transactional
    public void remove(Long userId) {
        User user = lockActiveUser(userId, "프로필 이미지 삭제");
        String previous = user.getProfileImageUrl();
        user.removeProfileImage();
        discard(user, previous);
    }

    private User lockActiveUser(Long userId, String action) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> rejected(action, "user_not_found", userId));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw rejected(action, "user_inactive", userId);
        }
        return user;
    }

    // 이전 값이 null이거나 본인 발급 URL이 아니면 빈 값이라 발행하지 않는다
    private void discard(User user, String previousUrl) {
        profileImageUrlVerifier.ownedObjectKey(user.getUuid(), previousUrl)
                .ifPresent(objectKey -> eventPublisher.publishEvent(new ProfileImageDiscardedEvent(user.getId(), objectKey)));
    }

    // 1.9 조회 거부(UserMeQueryService)와 같이 사유와 내부 ID만 남긴다
    private static BusinessException rejected(String action, String reason, Long userId) {
        log.info("{} 거부: reason={}, userId={}", action, reason, userId);
        return new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED);
    }
}
