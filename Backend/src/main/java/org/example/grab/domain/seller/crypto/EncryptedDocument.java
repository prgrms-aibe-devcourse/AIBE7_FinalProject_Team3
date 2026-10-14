package org.example.grab.domain.seller.crypto;

// 암호화 결과. ciphertext는 IV + 암호문, encryptedDek는 Base64(마스터키로 암호화한 DEK, IV 포함)다.
public record EncryptedDocument(byte[] ciphertext, String encryptedDek) {
}
