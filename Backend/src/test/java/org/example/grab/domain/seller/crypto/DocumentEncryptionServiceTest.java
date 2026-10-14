package org.example.grab.domain.seller.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentEncryptionServiceTest {

    // 디코딩하면 32바이트("grab-test-only-doc-encrypt-key32")인 테스트 전용 마스터키
    private static final String MASTER_KEY = Base64.getEncoder()
            .encodeToString("grab-test-only-doc-encrypt-key32".getBytes(StandardCharsets.UTF_8));

    private final DocumentEncryptionService service =
            new DocumentEncryptionService(new SellerDocumentEncryptionProperties(MASTER_KEY));

    @Test
    @DisplayName("암호화한 서류를 복호화하면 원본과 같다")
    void encryptThenDecryptRestoresPlaintext() {
        byte[] plaintext = "사업자등록증 이미지 바이트".getBytes(StandardCharsets.UTF_8);

        EncryptedDocument encrypted = service.encrypt(plaintext);
        byte[] decrypted = service.decrypt(encrypted.ciphertext(), encrypted.encryptedDek());

        assertThat(decrypted).isEqualTo(plaintext);
        // 평문이 암호문 바이트에 그대로 남아있지 않아야 한다
        assertThat(new String(encrypted.ciphertext(), StandardCharsets.UTF_8)).doesNotContain("사업자등록증");
    }

    @Test
    @DisplayName("암호문이 변조되면 복호화가 실패한다")
    void tamperedCiphertextFailsToDecrypt() {
        byte[] plaintext = "원본".getBytes(StandardCharsets.UTF_8);
        EncryptedDocument encrypted = service.encrypt(plaintext);
        byte[] tampered = Arrays.copyOf(encrypted.ciphertext(), encrypted.ciphertext().length);
        tampered[tampered.length - 1] ^= 0x01;

        assertThatThrownBy(() -> service.decrypt(tampered, encrypted.encryptedDek()))
                .isInstanceOf(IllegalStateException.class);
    }
}
