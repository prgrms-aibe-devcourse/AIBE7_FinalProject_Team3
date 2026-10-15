package org.example.grab.global.storage.supabase;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.DocumentStorage;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/*
    Supabase Storage에 암호문 바이트를 직접 업로드하는 클라이언트(GR-91). 도메인을 모르는 순수 인프라 계층이다.
    SupabaseStorageClient(서명 URL 발급)와 달리 서버가 바이트를 직접 올리고, 공개 URL은 발급하지 않는다.
 */
@Slf4j
public class SupabaseDocumentStorageClient implements DocumentStorage {

    private static final String OBJECT_PATH = "/storage/v1/object/";
    private static final String API_KEY_HEADER = "apikey";

    private final RestClient restClient;
    private final String baseUrl;
    private final String bucket;
    private final boolean configured;

    public SupabaseDocumentStorageClient(RestClient.Builder restClientBuilder, SupabaseStorageProperties properties) {
        this.baseUrl = stripTrailingSlash(properties.url());
        this.bucket = properties.documentsBucket();
        this.configured = StringUtils.hasText(baseUrl) && StringUtils.hasText(properties.secretKey());

        RestClient.Builder builder = restClientBuilder.clone();
        if (StringUtils.hasText(properties.secretKey())) {
            builder.defaultHeader(API_KEY_HEADER, properties.secretKey());
        }
        this.restClient = builder.build();
    }

    @Override
    public void store(String objectKey, byte[] data) {
        if (!configured) {
            log.error("Supabase Storage 설정이 없어 서류 업로드를 시도하지 않음");
            throw new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
        }
        try {
            restClient.post()
                    .uri(baseUrl + OBJECT_PATH + bucket + "/" + objectKey)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(data)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // 타임아웃·4xx·5xx. 응답·예외 메시지에 Secret Key를 넣지 않는다.
            log.warn("Supabase Storage 서류 업로드 실패: cause={}", e.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
        }
    }

    @Override
    public byte[] load(String objectKey) {
        if (!configured) {
            log.error("Supabase Storage 설정이 없어 서류 다운로드를 시도하지 않음");
            throw new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
        }
        try {
            return restClient.get()
                    .uri(baseUrl + OBJECT_PATH + bucket + "/" + objectKey)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException e) {
            // 타임아웃·4xx·5xx. 응답·예외 메시지에 Secret Key를 넣지 않는다.
            log.warn("Supabase Storage 서류 다운로드 실패: cause={}", e.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
        }
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
