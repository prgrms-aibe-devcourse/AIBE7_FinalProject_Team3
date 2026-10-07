package org.example.grab.domain.drop.upload.service;

import org.example.grab.domain.drop.upload.dto.ImageUploadUrlResponse;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.ImageStorage;
import org.example.grab.global.storage.SignedUploadUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DropImageUploadServiceTest {

    private ImageStorage imageStorage;
    private DropImageUploadService service;

    @BeforeEach
    void setUp() {
        imageStorage = mock(ImageStorage.class);
        service = new DropImageUploadService(imageStorage);
    }

    @Test
    @DisplayName("contentType에서 확장자를 정해 images/{imageId}.{ext} 키로 발급하고 2시간 만료를 담는다")
    void issue() {
        // given
        given(imageStorage.createSignedUploadUrl(anyString()))
                .willReturn(new SignedUploadUrl("https://storage.example.com/signed", "https://storage.example.com/public"));

        // when
        OffsetDateTime before = OffsetDateTime.now();
        ImageUploadUrlResponse response = service.issue("image/png", 1024L);
        OffsetDateTime after = OffsetDateTime.now();

        // then
        ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
        verify(imageStorage).createSignedUploadUrl(objectKey.capture());
        assertThat(objectKey.getValue()).matches("images/[0-9a-f-]{36}\\.png");
        assertThat(response.imageId()).isNotNull();
        assertThat(objectKey.getValue()).contains(response.imageId().toString());
        assertThat(response.uploadUrl()).isEqualTo("https://storage.example.com/signed");
        assertThat(response.imageUrl()).isEqualTo("https://storage.example.com/public");
        assertThat(response.expiresAt())
                .isBetween(before.plus(DropImageUploadService.UPLOAD_URL_TTL),
                        after.plus(DropImageUploadService.UPLOAD_URL_TTL));
    }

    @Test
    @DisplayName("jpeg·webp 확장자를 contentType에서 결정하고 fileName은 쓰지 않는다")
    void mapsExtensionsFromContentType() {
        // given
        given(imageStorage.createSignedUploadUrl(anyString()))
                .willReturn(new SignedUploadUrl("u", "i"));

        // when
        service.issue("image/jpeg", 1024L);
        service.issue("image/webp", 1024L);

        // then
        ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
        verify(imageStorage, org.mockito.Mockito.times(2)).createSignedUploadUrl(objectKey.capture());
        assertThat(objectKey.getAllValues().get(0)).endsWith(".jpg");
        assertThat(objectKey.getAllValues().get(1)).endsWith(".webp");
    }

    @Test
    @DisplayName("허용하지 않는 contentType은 400 VALIDATION_FAILED(contentType)이고 발급하지 않는다")
    void rejectsUnsupportedContentType() {
        // when & then
        assertThatThrownBy(() -> service.issue("image/gif", 1024L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(fieldNames(e)).containsExactly("contentType");
                });
        verify(imageStorage, never()).createSignedUploadUrl(anyString());
    }

    @Test
    @DisplayName("최대 크기를 넘으면 400 VALIDATION_FAILED(fileSize)이고 발급하지 않는다")
    void rejectsOversize() {
        // when & then
        assertThatThrownBy(() -> service.issue("image/png", DropImageUploadService.MAX_FILE_SIZE + 1))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(fieldNames(e)).containsExactly("fileSize");
                });
        verify(imageStorage, never()).createSignedUploadUrl(anyString());
    }

    private static List<String> fieldNames(BusinessException e) {
        return e.getFieldErrors().stream().map(ErrorResponse.FieldError::field).toList();
    }
}
