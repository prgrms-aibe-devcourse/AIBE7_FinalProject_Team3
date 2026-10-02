package org.example.grab.global.storage.supabase;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Supabase Storage 설정 바인딩. 실제 호출 클라이언트(SupabaseStorageClient) 빈은 발급 API 구현(GR-51)에서 추가한다.
 */
@Configuration
@EnableConfigurationProperties(SupabaseStorageProperties.class)
public class SupabaseStorageConfig {
}
