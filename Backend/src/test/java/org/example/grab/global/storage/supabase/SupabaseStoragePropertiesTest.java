package org.example.grab.global.storage.supabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// GR-130 M05-01: 버킷이 셋이 된 뒤에도 toString이 Secret Key를 가리고 버킷 이름은 그대로 보여 준다
class SupabaseStoragePropertiesTest {

    @Test
    @DisplayName("toString은 Secret Key 원문 대신 masked를 쓰고 세 버킷 이름을 보여 준다")
    void masksSecretKey() {
        // given
        SupabaseStorageProperties properties = new SupabaseStorageProperties("https://project.supabase.co",
                "sb_secret_test", "drop-images", "seller-documents", "profile-images",
                Duration.ofSeconds(3), Duration.ofSeconds(5));

        // when
        String text = properties.toString();

        // then
        assertThat(text)
                .doesNotContain("sb_secret_test")
                .contains("secretKey=masked", "dropImagesBucket=drop-images",
                        "documentsBucket=seller-documents", "profileImagesBucket=profile-images");
    }

    @Test
    @DisplayName("Secret Key가 비어 있으면 empty로 표시한다")
    void showsEmptySecretKey() {
        // given
        SupabaseStorageProperties properties = new SupabaseStorageProperties("", " ", "drop-images",
                "seller-documents", "profile-images", Duration.ofSeconds(3), Duration.ofSeconds(5));

        // when & then
        assertThat(properties.toString()).contains("secretKey=empty");
    }
}
