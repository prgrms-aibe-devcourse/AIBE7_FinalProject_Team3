package org.example.grab.domain.user.service;

import org.example.grab.domain.user.event.ProfileImageDiscardedEvent;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.ProfileImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// GR-130 M05-02: 이전 프로필 이미지 삭제가 실패해도 예외를 밖으로 내지 않고 WARN만 남긴다(M00-04 2번)
@ExtendWith(OutputCaptureExtension.class)
class ProfileImageCleanupListenerTest {

    private static final Long USER_ID = 7L;
    private static final String OBJECT_KEY =
            "profiles/6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.png";

    private ProfileImageStorage profileImageStorage;
    private ProfileImageCleanupListener listener;

    @BeforeEach
    void setUp() {
        profileImageStorage = mock(ProfileImageStorage.class);
        listener = new ProfileImageCleanupListener(profileImageStorage);
    }

    @Test
    @DisplayName("이벤트의 객체 키로 저장소 삭제를 호출한다")
    void deletesDiscardedObject() {
        // when
        listener.onProfileImageDiscarded(new ProfileImageDiscardedEvent(USER_ID, OBJECT_KEY));

        // then
        verify(profileImageStorage).delete(OBJECT_KEY);
    }

    @Test
    @DisplayName("삭제가 실패하면 예외 없이 끝내고 WARN에 내부 ID와 사유만 남긴다")
    void swallowsDeleteFailure(CapturedOutput output) {
        // given
        doThrow(new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR)).when(profileImageStorage).delete(OBJECT_KEY);

        // when & then
        assertThatNoException().isThrownBy(
                () -> listener.onProfileImageDiscarded(new ProfileImageDiscardedEvent(USER_ID, OBJECT_KEY)));
        List<String> lines = output.getAll().lines().filter(line -> line.contains("이전 프로필 이미지 삭제 실패")).toList();
        assertThat(lines).singleElement().satisfies(line -> assertThat(line)
                .contains("WARN", "userId=" + USER_ID, "cause=BusinessException")
                .doesNotContain(OBJECT_KEY)
                .doesNotContain("profiles/"));
    }
}
