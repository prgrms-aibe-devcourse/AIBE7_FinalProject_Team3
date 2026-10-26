package org.example.grab.global.storage.supabase;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.ImageStorage;
import org.example.grab.global.storage.SignedUploadUrl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Supabase Storage 서명 업로드 URL 발급·객체 삭제 클라이언트. 도메인을 모르는 순수 인프라 계층이다.
 * 객체 키는 호출자가 만든다(예: images/{imageId}.{ext}).
 * 인스턴스마다 버킷 하나에 묶인다. DROP 이미지(dropImagesBucket)와 프로필 이미지(profileImagesBucket, GR-130)는 각각 다른 인스턴스다.
 */
@Slf4j
public class SupabaseStorageClient implements ImageStorage {

    private static final String STORAGE_PATH = "/storage/v1";
    private static final String SIGNED_UPLOAD_PATH = STORAGE_PATH + "/object/upload/sign/";
    private static final String PUBLIC_URL_PATH = STORAGE_PATH + "/object/public/";
    private static final String OBJECT_PATH = STORAGE_PATH + "/object/";
    private static final String API_KEY_HEADER = "apikey";

    private final RestClient restClient;
    private final String baseUrl;
    private final String bucket;
    private final boolean configured;

    // TossPaymentGateway와 같은 방식으로 builder를 받아, 테스트는 같은 builder에 MockRestServiceServer를 묶는다.
    public SupabaseStorageClient(RestClient.Builder restClientBuilder, SupabaseStorageProperties properties, String bucket) {
        this.baseUrl = stripTrailingSlash(properties.url()); // 공백과 끝의 /를 제거
        this.bucket = bucket;
        this.configured = StringUtils.hasText(baseUrl) && StringUtils.hasText(properties.secretKey()); // 프로젝트 주소와 Secret Key가 모두 존재하면 true

        RestClient.Builder builder = restClientBuilder.clone();
        if (StringUtils.hasText(properties.secretKey())) {
            builder.defaultHeader(API_KEY_HEADER, properties.secretKey());
        }
        this.restClient = builder.build();
    }

    @Override
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
            // 타임아웃·4xx·5xx. 응답·예외 메시지에 서명 토큰과 Secret Key를 넣지 않는다.
            log.warn("Supabase Storage 업로드 URL 발급 실패: cause={}", e.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
        }
    }

    // DROP 저장 시 imageUrl이 이 버킷의 공개 URL인지 검증할 때도 쓴다.
    @Override
    public String publicUrl(String objectKey) {
        return baseUrl + PUBLIC_URL_PATH + bucket + "/" + objectKey;
    }

    /*
        객체 하나를 지운다(GR-130 M00-04 프로필 이미지 교체·삭제). ImageStorage에는 두지 않아 DROP 이미지 쪽에서는 호출되지 않는다.
        단건 삭제(DELETE /object/{bucket}/{key})는 없는 객체에 404를 주므로, 없는 객체를 건너뛰고 200을 주는
        일괄 삭제(DELETE /object/{bucket}, {"prefixes": [...]})를 써서 같은 키를 다시 지워도 성공하게 한다.
     */
    public void delete(String objectKey) {
        if (!configured) {
            log.error("Supabase Storage 설정이 없어 객체 삭제를 시도하지 않음");
            throw new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
        }
        try {
            restClient.method(HttpMethod.DELETE)
                    .uri(baseUrl + OBJECT_PATH + bucket)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("prefixes", List.of(objectKey)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // 타임아웃·4xx·5xx. 응답·예외 메시지·로그에 Secret Key와 객체 키를 넣지 않는다.
            log.warn("Supabase Storage 객체 삭제 실패: cause={}", e.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
        }
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
