package org.example.grab.domain.seller.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.Base64;

/*
    판매자 서류 봉투 암호화(GR-91)의 마스터키 설정. 값이 잘못되면 생성자에서 예외를 던져 애플리케이션이 기동하지 않게 한다.
    masterKey는 기본값 없이 환경변수 SELLER_DOCUMENT_ENCRYPTION_MASTER_KEY로만 주입한다(AccessTokenProperties와 같은 방식).
    KMS 연동은 하지 않는다(GR-91).
 */
@ConfigurationProperties("grab.seller.document-encryption")
public record SellerDocumentEncryptionProperties(
        // Base64로 인코딩한 AES-256 키. 디코딩한 값이 32바이트(256비트)여야 한다
        String masterKey
) {

    private static final int REQUIRED_KEY_BYTES = 32;

    public SellerDocumentEncryptionProperties {
        validateMasterKey(masterKey);
    }

    public byte[] masterKeyBytes() {
        return Base64.getDecoder().decode(masterKey);
    }

    private static void validateMasterKey(String masterKey) {
        if (!StringUtils.hasText(masterKey)) {
            throw new IllegalArgumentException(
                    "grab.seller.document-encryption.master-key(SELLER_DOCUMENT_ENCRYPTION_MASTER_KEY) 값은 비어 있을 수 없습니다.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(masterKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "grab.seller.document-encryption.master-key(SELLER_DOCUMENT_ENCRYPTION_MASTER_KEY) 값은 Base64 형식이어야 합니다.");
        }
        if (decoded.length != REQUIRED_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "grab.seller.document-encryption.master-key(SELLER_DOCUMENT_ENCRYPTION_MASTER_KEY) 값은 디코딩 후 "
                            + REQUIRED_KEY_BYTES + "바이트여야 합니다. 현재 " + decoded.length + "바이트");
        }
    }

    // record 기본 toString은 마스터키 원문을 출력하므로 로그·예외 메시지에 새지 않도록 가린다.
    @Override
    public String toString() {
        return "SellerDocumentEncryptionProperties[masterKey=masked]";
    }
}
