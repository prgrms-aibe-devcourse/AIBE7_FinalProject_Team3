package org.example.grab.domain.seller.service;

import org.example.grab.domain.seller.crypto.DocumentEncryptionService;
import org.example.grab.domain.seller.dto.SellerDocumentView;
import org.example.grab.domain.seller.entity.DocumentType;
import org.example.grab.domain.seller.entity.Seller;
import org.example.grab.domain.seller.entity.SellerDocument;
import org.example.grab.domain.seller.entity.SellerDocumentAccessLog;
import org.example.grab.domain.seller.error.SellerErrorCode;
import org.example.grab.domain.seller.repository.SellerDocumentAccessLogRepository;
import org.example.grab.domain.seller.repository.SellerDocumentRepository;
import org.example.grab.domain.seller.repository.SellerRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.storage.DocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SellerDocumentViewServiceTest {

    private static final Long ADMIN_ID = 100L;
    private static final Long SELLER_ID = 1L;
    private static final byte[] CIPHERTEXT = "암호문".getBytes(StandardCharsets.UTF_8);
    private static final String ENCRYPTED_DEK = "encrypted-dek";
    private static final String OBJECT_KEY = "object-key";

    private SellerRepository sellerRepository;
    private SellerDocumentRepository sellerDocumentRepository;
    private SellerDocumentAccessLogRepository sellerDocumentAccessLogRepository;
    private DocumentStorage documentStorage;
    private DocumentEncryptionService documentEncryptionService;
    private SellerDocumentViewService service;

    @BeforeEach
    void setUp() {
        sellerRepository = mock(SellerRepository.class);
        sellerDocumentRepository = mock(SellerDocumentRepository.class);
        sellerDocumentAccessLogRepository = mock(SellerDocumentAccessLogRepository.class);
        documentStorage = mock(DocumentStorage.class);
        documentEncryptionService = mock(DocumentEncryptionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T05:00:00Z"), ZoneOffset.UTC);
        service = new SellerDocumentViewService(sellerRepository, sellerDocumentRepository,
                sellerDocumentAccessLogRepository, documentStorage, documentEncryptionService, clock);
    }

    @Test
    @DisplayName("신청이 없으면 404로 거부하고 버킷·로그에 접근하지 않는다")
    void applicationNotFound() {
        UUID applicationId = UUID.randomUUID();
        given(sellerRepository.findByUuid(applicationId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.view(applicationId, DocumentType.BUSINESS_LICENSE, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND);
        verifyNoInteractions(documentStorage, sellerDocumentAccessLogRepository);
    }

    @Test
    @DisplayName("해당 유형의 서류가 없으면 404로 거부한다")
    void documentNotFound() {
        Seller seller = sellerWithId();
        UUID applicationId = seller.getUuid();
        given(sellerRepository.findByUuid(applicationId)).willReturn(Optional.of(seller));
        given(sellerDocumentRepository.findBySellerIdAndDocumentType(SELLER_ID, DocumentType.ID_CARD))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.view(applicationId, DocumentType.ID_CARD, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND);
        verifyNoInteractions(documentStorage, sellerDocumentAccessLogRepository);
    }

    @Test
    @DisplayName("복호화한 이미지에 워터마크를 합성해 반환하고 접근 로그를 남긴다")
    void viewsDocumentAndRecordsAccessLog() throws IOException {
        Seller seller = sellerWithId();
        UUID applicationId = seller.getUuid();
        SellerDocument document = SellerDocument.upload(UUID.randomUUID(), 1L, DocumentType.BUSINESS_LICENSE,
                "image/png", OBJECT_KEY, ENCRYPTED_DEK);
        byte[] plainImage = samplePng();
        given(sellerRepository.findByUuid(applicationId)).willReturn(Optional.of(seller));
        given(sellerDocumentRepository.findBySellerIdAndDocumentType(SELLER_ID, DocumentType.BUSINESS_LICENSE))
                .willReturn(Optional.of(document));
        given(documentStorage.load(OBJECT_KEY)).willReturn(CIPHERTEXT);
        given(documentEncryptionService.decrypt(CIPHERTEXT, ENCRYPTED_DEK)).willReturn(plainImage);

        SellerDocumentView view = service.view(applicationId, DocumentType.BUSINESS_LICENSE, ADMIN_ID);

        assertThat(view.contentType()).isEqualTo("image/png");
        assertThat(view.imageBytes()).isNotEqualTo(plainImage);
        assertThat(ImageIO.read(new ByteArrayInputStream(view.imageBytes()))).isNotNull();

        ArgumentCaptor<SellerDocumentAccessLog> captor = ArgumentCaptor.forClass(SellerDocumentAccessLog.class);
        verify(sellerDocumentAccessLogRepository).save(captor.capture());
        assertThat(captor.getValue().getAdminId()).isEqualTo(ADMIN_ID);
        assertThat(captor.getValue().getSellerId()).isEqualTo(SELLER_ID);
        assertThat(captor.getValue().getDocumentType()).isEqualTo(DocumentType.BUSINESS_LICENSE);
        assertThat(captor.getValue().getAccessedAt()).isEqualTo(OffsetDateTime.parse("2026-10-14T05:00:00Z"));
    }

    @Test
    @DisplayName("ImageIO가 지원하지 않는 형식이면 빈 이미지를 돌려주지 않고 실패한다")
    void viewFailsWhenImageFormatUnsupported() throws IOException {
        Seller seller = sellerWithId();
        UUID applicationId = seller.getUuid();
        SellerDocument document = SellerDocument.upload(UUID.randomUUID(), 1L, DocumentType.BUSINESS_LICENSE,
                "image/x-made-up", OBJECT_KEY, ENCRYPTED_DEK);
        byte[] plainImage = samplePng();
        given(sellerRepository.findByUuid(applicationId)).willReturn(Optional.of(seller));
        given(sellerDocumentRepository.findBySellerIdAndDocumentType(SELLER_ID, DocumentType.BUSINESS_LICENSE))
                .willReturn(Optional.of(document));
        given(documentStorage.load(OBJECT_KEY)).willReturn(CIPHERTEXT);
        given(documentEncryptionService.decrypt(CIPHERTEXT, ENCRYPTED_DEK)).willReturn(plainImage);

        assertThatThrownBy(() -> service.view(applicationId, DocumentType.BUSINESS_LICENSE, ADMIN_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x-made-up");
        verifyNoInteractions(sellerDocumentAccessLogRepository);
    }

    private static Seller sellerWithId() {
        Seller seller = Seller.apply(1L, "길동상점", "seller@example.com", "1234567890", null,
                OffsetDateTime.now(ZoneOffset.UTC));
        ReflectionTestUtils.setField(seller, "id", SELLER_ID);
        return seller;
    }

    private static byte[] samplePng() throws IOException {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 40, 40);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
