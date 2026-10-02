package org.example.grab.global.storage.supabase;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Supabase Storage 연동 설정(grab.storage.supabase.*).
 *
 * @param url            프로젝트 URL. 비어 있으면 서명 업로드 URL 발급을 시도하지 않는다
 * @param secretKey      RLS를 우회하는 서버 전용 키. 저장소·이미지·로그·응답에 남기지 않는다
 * @param bucket         DROP 이미지 버킷 이름. 버킷의 허용 MIME·크기 제한과 서버 검증값을 같게 유지한다
 */
@ConfigurationProperties("grab.storage.supabase")
public record SupabaseStorageProperties(
        @DefaultValue("") String url,
        @DefaultValue("") String secretKey,
        @DefaultValue("drop-images") String bucket,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout
) {

    // record 기본 toString은 Secret Key 원문을 출력하므로 로그·예외 메시지에 새지 않도록 가린다.
    @Override
    public String toString() {
        return "SupabaseStorageProperties[url=" + url
                + ", secretKey=" + (StringUtils.hasText(secretKey) ? "masked" : "empty")
                + ", bucket=" + bucket
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
