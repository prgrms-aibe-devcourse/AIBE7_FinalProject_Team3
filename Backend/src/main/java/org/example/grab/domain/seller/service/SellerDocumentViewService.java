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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/*
    운영자 서류 전용 뷰어(GR-92). presigned URL을 발급하지 않고, 매 요청마다
    서버가 복호화 + 워터마크 합성까지 끝낸 이미지 바이트를 직접 돌려준다.
 */
@Service
public class SellerDocumentViewService {

    private static final DateTimeFormatter WATERMARK_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SellerRepository sellerRepository;
    private final SellerDocumentRepository sellerDocumentRepository;
    private final SellerDocumentAccessLogRepository sellerDocumentAccessLogRepository;
    private final DocumentStorage documentStorage;
    private final DocumentEncryptionService documentEncryptionService;
    private final Clock clock;

    @Autowired
    public SellerDocumentViewService(SellerRepository sellerRepository,
                                     SellerDocumentRepository sellerDocumentRepository,
                                     SellerDocumentAccessLogRepository sellerDocumentAccessLogRepository,
                                     DocumentStorage documentStorage,
                                     DocumentEncryptionService documentEncryptionService) {
        this(sellerRepository, sellerDocumentRepository, sellerDocumentAccessLogRepository, documentStorage,
                documentEncryptionService, Clock.systemUTC());
    }

    SellerDocumentViewService(SellerRepository sellerRepository,
                              SellerDocumentRepository sellerDocumentRepository,
                              SellerDocumentAccessLogRepository sellerDocumentAccessLogRepository,
                              DocumentStorage documentStorage,
                              DocumentEncryptionService documentEncryptionService,
                              Clock clock) {
        this.sellerRepository = sellerRepository;
        this.sellerDocumentRepository = sellerDocumentRepository;
        this.sellerDocumentAccessLogRepository = sellerDocumentAccessLogRepository;
        this.documentStorage = documentStorage;
        this.documentEncryptionService = documentEncryptionService;
        this.clock = clock;
    }

    @Transactional
    public SellerDocumentView view(UUID applicationId, DocumentType documentType, Long adminId) {
        Seller seller = sellerRepository.findByUuid(applicationId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));
        SellerDocument document = sellerDocumentRepository
                .findBySellerIdAndDocumentType(seller.getId(), documentType)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));

        byte[] ciphertext = documentStorage.load(document.getEncryptedObjectKey());
        byte[] plaintext = documentEncryptionService.decrypt(ciphertext, document.getEncryptedDek());

        OffsetDateTime accessedAt = OffsetDateTime.now(clock);
        byte[] watermarked = watermark(plaintext, document.getContentType(), adminId, accessedAt);

        sellerDocumentAccessLogRepository.save(
                SellerDocumentAccessLog.record(adminId, seller.getId(), documentType, accessedAt));

        return new SellerDocumentView(watermarked, document.getContentType());
    }

    // ponytail: 화면 캡처 방지는 서버가 막을 방법이 없어 프론트 UI 담당으로 둔다(GR-92)
    private static byte[] watermark(byte[] imageBytes, String contentType, Long adminId, OffsetDateTime accessedAt) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new IllegalStateException("서류 이미지를 해석할 수 없습니다.");
            }
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(14, image.getWidth() / 40)));
            graphics.setColor(new Color(255, 0, 0, 160));
            String text = "admin #" + adminId + " · " + WATERMARK_TIME_FORMAT.format(accessedAt);
            graphics.drawString(text, 16, image.getHeight() - 16);
            graphics.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String formatName = formatNameOf(contentType);
            if (!ImageIO.write(image, formatName, out)) {
                throw new IllegalStateException("서류 이미지를 '" + formatName + "' 형식으로 인코딩할 수 없습니다.");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("서류 워터마크 합성에 실패했습니다.", e);
        }
    }

    private static String formatNameOf(String contentType) {
        int slash = contentType.indexOf('/');
        return slash >= 0 ? contentType.substring(slash + 1) : "png";
    }
}
