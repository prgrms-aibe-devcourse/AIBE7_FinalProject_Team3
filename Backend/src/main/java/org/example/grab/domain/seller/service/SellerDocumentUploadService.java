package org.example.grab.domain.seller.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.seller.crypto.DocumentEncryptionService;
import org.example.grab.domain.seller.crypto.EncryptedDocument;
import org.example.grab.domain.seller.dto.SellerDocumentUploadResponse;
import org.example.grab.domain.seller.entity.DocumentType;
import org.example.grab.domain.seller.entity.SellerDocument;
import org.example.grab.domain.seller.repository.SellerDocumentRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.DocumentStorage;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SellerDocumentUploadService {

    private final SellerDocumentRepository sellerDocumentRepository;
    private final DocumentEncryptionService documentEncryptionService;
    private final DocumentStorage documentStorage;

    // 서버가 받아 암호화 후 비공개 버킷에 저장한다(GR-91). 원본은 메모리에서만 다루고 저장하지 않는다.
    @Transactional
    public SellerDocumentUploadResponse upload(Long userId, DocumentType documentType, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }

        byte[] plaintext = readBytes(file);
        if (!isDecodableImage(plaintext)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        EncryptedDocument encrypted = documentEncryptionService.encrypt(plaintext);

        UUID documentId = UUID.randomUUID();
        String objectKey = documentId.toString();
        documentStorage.store(objectKey, encrypted.ciphertext());

        String contentType = file.getContentType() != null
                ? file.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        SellerDocument document = SellerDocument.upload(
                documentId, userId, documentType, contentType, objectKey, encrypted.encryptedDek());
        sellerDocumentRepository.save(document);

        return new SellerDocumentUploadResponse(documentId, documentType);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    // 운영자 뷰어(GR-92)가 열람 시점에 디코딩 실패를 겪지 않도록, 암호화 전에 이미지로 해석되는 파일인지 먼저 확인한다.
    private static boolean isDecodableImage(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes)) != null;
        } catch (IOException e) {
            return false;
        }
    }
}
