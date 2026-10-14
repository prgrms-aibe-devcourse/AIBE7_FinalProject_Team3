package org.example.grab.domain.seller.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/*
    서류 봉투 암호화(GR-91). 파일마다 랜덤 DEK로 AES-256-GCM 암호화하고,
    DEK는 환경변수 마스터키로 한 번 더 암호화해 함께 저장한다(KMS 연동 없음).
 */
@Component
public class DocumentEncryptionService {

    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int DEK_BITS = 256;

    private final SecretKey masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public DocumentEncryptionService(SellerDocumentEncryptionProperties properties) {
        this.masterKey = new SecretKeySpec(properties.masterKeyBytes(), KEY_ALGORITHM);
    }

    public EncryptedDocument encrypt(byte[] plaintext) {
        SecretKey dek = generateDek();
        byte[] ciphertext = seal(dek, plaintext);
        byte[] encryptedDek = seal(masterKey, dek.getEncoded());
        return new EncryptedDocument(ciphertext, Base64.getEncoder().encodeToString(encryptedDek));
    }

    public byte[] decrypt(byte[] ciphertext, String encryptedDekBase64) {
        byte[] dekBytes = unseal(masterKey, Base64.getDecoder().decode(encryptedDekBase64));
        SecretKey dek = new SecretKeySpec(dekBytes, KEY_ALGORITHM);
        return unseal(dek, ciphertext);
    }

    // IV(12바이트) + 암호문(태그 포함)을 이어 붙인다.
    private byte[] seal(SecretKey key, byte[] plaintext) {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("서류 암호화에 실패했습니다.", e);
        }
    }

    private byte[] unseal(SecretKey key, byte[] payload) {
        byte[] iv = Arrays.copyOfRange(payload, 0, GCM_IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(payload, GCM_IV_BYTES, payload.length);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(encrypted);
        } catch (BadPaddingException | IllegalBlockSizeException e) {
            throw new IllegalStateException("서류 복호화에 실패했습니다. 암호문이 손상되었을 수 있습니다.", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("서류 복호화에 실패했습니다.", e);
        }
    }

    private static SecretKey generateDek() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(KEY_ALGORITHM);
            generator.init(DEK_BITS);
            return generator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("DEK 생성에 실패했습니다.", e);
        }
    }
}
