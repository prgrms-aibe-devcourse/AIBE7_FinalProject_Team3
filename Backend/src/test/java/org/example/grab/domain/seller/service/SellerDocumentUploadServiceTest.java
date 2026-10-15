package org.example.grab.domain.seller.service;

import org.example.grab.domain.seller.crypto.DocumentEncryptionService;
import org.example.grab.domain.seller.crypto.EncryptedDocument;
import org.example.grab.domain.seller.dto.SellerDocumentUploadResponse;
import org.example.grab.domain.seller.entity.DocumentType;
import org.example.grab.domain.seller.repository.SellerDocumentRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.DocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SellerDocumentUploadServiceTest {

    private static final Long USER_ID = 1L;

    private SellerDocumentRepository sellerDocumentRepository;
    private DocumentEncryptionService documentEncryptionService;
    private DocumentStorage documentStorage;
    private SellerDocumentUploadService service;

    @BeforeEach
    void setUp() {
        sellerDocumentRepository = mock(SellerDocumentRepository.class);
        documentEncryptionService = mock(DocumentEncryptionService.class);
        documentStorage = mock(DocumentStorage.class);
        service = new SellerDocumentUploadService(sellerDocumentRepository, documentEncryptionService, documentStorage);
    }

    @Test
    @DisplayName("이미지로 디코딩되는 파일은 암호화해서 버킷에 저장하고 documentId를 발급한다")
    void uploadsDecodableImage() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", samplePng());
        given(documentEncryptionService.encrypt(any()))
                .willReturn(new EncryptedDocument("ciphertext".getBytes(StandardCharsets.UTF_8), "encrypted-dek"));

        SellerDocumentUploadResponse response = service.upload(USER_ID, DocumentType.BUSINESS_LICENSE, file);

        assertThat(response.documentType()).isEqualTo(DocumentType.BUSINESS_LICENSE);
        verify(documentStorage).store(any(), any());
        verify(sellerDocumentRepository).save(any());
    }

    @Test
    @DisplayName("파일이 없거나 비어 있으면 400 INVALID_REQUEST로 거부한다")
    void rejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.upload(USER_ID, DocumentType.BUSINESS_LICENSE, empty))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);
        verifyNeverEncryptedOrStored();
    }

    @Test
    @DisplayName("이미지로 디코딩할 수 없는 파일은 400 VALIDATION_FAILED로 거부하고 암호화·저장을 시도하지 않는다")
    void rejectsUndecodableFile() {
        MockMultipartFile notAnImage = new MockMultipartFile(
                "file", "fake.png", "image/png", "이건 이미지가 아니다".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.upload(USER_ID, DocumentType.BUSINESS_LICENSE, notAnImage))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        verifyNeverEncryptedOrStored();
    }

    private void verifyNeverEncryptedOrStored() {
        verify(documentEncryptionService, never()).encrypt(any());
        verify(documentStorage, never()).store(any(), any());
        verify(sellerDocumentRepository, never()).save(any());
    }

    private static byte[] samplePng() throws IOException {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
