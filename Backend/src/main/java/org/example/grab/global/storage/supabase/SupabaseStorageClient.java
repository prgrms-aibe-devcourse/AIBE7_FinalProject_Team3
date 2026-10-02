package org.example.grab.global.storage.supabase;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Supabase Storage 서명 업로드 URL 발급 클라이언트. 도메인을 모르는 순수 인프라 계층이다.
 * 객체 키는 호출자가 만든다(예: images/{imageId}.{ext}).
 */
@Slf4j
public class SupabaseStorageClient {

    private static final String STORAGE_PATH = "/storage/v1";
    private static final String SIGNED_UPLOAD_PATH = STORAGE_PATH + "/object/upload/sign/";
    private static final String PUBLIC_URL_PATH = STORAGE_PATH + "/object/public/";
    private static final String API_KEY_HEADER = "apikey";

    private final RestClient restClient;
    private final String baseUrl;
    private final String bucket;
    private final boolean configured;

    // TossPaymentGateway와 같은 방식으로 builder를 받아, 테스트는 같은 builder에 MockRestServiceServer를 묶는다.
    public SupabaseStorageClient(RestClient.Builder restClientBuilder, SupabaseStorageProperties properties) {
        this.baseUrl = stripTrailingSlash(properties.url());
        this.bucket = properties.bucket();
        this.configured = StringUtils.hasText(baseUrl) && StringUtils.hasText(properties.serviceRoleKey());

        RestClient.Builder builder = restClientBuilder.clone();
        if (StringUtils.hasText(properties.serviceRoleKey())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceRoleKey())
                    .defaultHeader(API_KEY_HEADER, properties.serviceRoleKey());
        }
        this.restClient = builder.build();
    }

    public SignedUploadUrl createSignedUploadUrl(String objectKey) {
        if (!configured) {
            log.error("Supabase Storage 설정이 없어 업로드 URL 발급을 시도하지 않음");
            throw new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
        }
        try {
            SupabaseSignedUploadResponse response = restClient.post()
                    .uri(baseUrl + SIGNED_UPLOAD_PATH + bucket + "/" + objectKey)
                    .retrieve()
                    .body(SupabaseSignedUploadResponse.class);
            if (response == null || !StringUtils.hasText(response.url())) {
                log.warn("Supabase Storage 응답에 서명 URL이 없음");
                throw new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
            }
            return new SignedUploadUrl(baseUrl + STORAGE_PATH + normalize(response.url()), publicUrl(objectKey));
        } catch (RestClientException e) {
            // 타임아웃·4xx·5xx. 응답·예외 메시지에 서명 토큰과 서비스 롤 키를 넣지 않는다.
            log.warn("Supabase Storage 업로드 URL 발급 실패: cause={}", e.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
        }
    }

    // DROP 저장 시 imageUrl이 이 버킷의 공개 URL인지 검증할 때도 쓴다.
    public String publicUrl(String objectKey) {
        return baseUrl + PUBLIC_URL_PATH + bucket + "/" + objectKey;
    }

    private static String normalize(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
