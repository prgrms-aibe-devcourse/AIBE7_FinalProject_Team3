package org.example.grab.global.storage.supabase;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.storage.SignedUploadUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// GR-130 M05-01: 프로필 이미지 버킷에 묶인 저장소의 서명 URL·공개 URL·객체 삭제 요청과 실패 변환
class SupabaseProfileImageStorageClientTest {

    private static final String BASE_URL = "https://project.supabase.co";
    private static final String SECRET_KEY = "sb_secret_test";
    private static final String BUCKET = "profile-images";
    private static final String OBJECT_KEY =
            "profiles/6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.png";
    private static final String DELETE_URL = BASE_URL + "/storage/v1/object/" + BUCKET;

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private SupabaseProfileImageStorageClient client(SupabaseStorageProperties properties) {
        return new SupabaseProfileImageStorageClient(
                new SupabaseStorageClient(builder, properties, properties.profileImagesBucket()));
    }

    @Test
    @DisplayName("서명 URL·공개 URL을 프로필 이미지 버킷 경로로 만든다")
    void createsUrlsInProfileBucket() {
        // given
        SupabaseProfileImageStorageClient client = client(properties(SECRET_KEY));
        server.expect(requestTo(BASE_URL + "/storage/v1/object/upload/sign/" + BUCKET + "/" + OBJECT_KEY))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", SECRET_KEY))
                .andRespond(withSuccess(
                        "{\"url\":\"/object/upload/sign/" + BUCKET + "/" + OBJECT_KEY + "?token=signed-token\"}",
                        MediaType.APPLICATION_JSON));

        // when
        SignedUploadUrl result = client.createSignedUploadUrl(OBJECT_KEY);

        // then
        server.verify();
        assertThat(result.uploadUrl()).isEqualTo(
                BASE_URL + "/storage/v1/object/upload/sign/" + BUCKET + "/" + OBJECT_KEY + "?token=signed-token");
        assertThat(result.imageUrl()).isEqualTo(BASE_URL + "/storage/v1/object/public/" + BUCKET + "/" + OBJECT_KEY);
        assertThat(client.publicUrl(OBJECT_KEY)).isEqualTo(result.imageUrl());
    }

    @Test
    @DisplayName("설정 빈은 DROP 이미지와 프로필 이미지를 각자의 버킷 공개 URL로 만든다")
    void configKeepsDropBucketSeparate() {
        // given
        SupabaseStorageConfig config = new SupabaseStorageConfig();
        SupabaseStorageProperties properties = properties(SECRET_KEY);

        // when
        String dropUrl = config.supabaseStorageClient(properties).publicUrl("images/a.png");
        String profileUrl = config.supabaseProfileImageStorageClient(properties).publicUrl("profiles/a.png");

        // then
        assertThat(dropUrl).isEqualTo(BASE_URL + "/storage/v1/object/public/drop-images/images/a.png");
        assertThat(profileUrl).isEqualTo(BASE_URL + "/storage/v1/object/public/profile-images/profiles/a.png");
    }

    // M01-03: 단건 삭제 대신 일괄 삭제 API를 쓴다
    @Test
    @DisplayName("객체 삭제는 버킷 일괄 삭제 경로로 키를 prefixes 본문에 담아 인증 헤더와 함께 보낸다")
    void deletesObject() {
        // given
        SupabaseProfileImageStorageClient client = client(properties(SECRET_KEY));
        server.expect(requestTo(DELETE_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("apikey", SECRET_KEY))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().json("{\"prefixes\":[\"" + OBJECT_KEY + "\"]}", true))
                .andRespond(withSuccess("[{\"name\":\"" + OBJECT_KEY + "\"}]", MediaType.APPLICATION_JSON));

        // when
        client.delete(OBJECT_KEY);

        // then
        server.verify();
    }

    // M01-03 실제 확인: 없는 키의 일괄 삭제는 200과 빈 배열이다
    @Test
    @DisplayName("없는 객체를 지워 빈 배열을 받아도 성공한다")
    void deletingMissingObjectSucceeds() {
        // given
        SupabaseProfileImageStorageClient client = client(properties(SECRET_KEY));
        server.expect(requestTo(DELETE_URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        // when & then
        assertThatNoException().isThrownBy(() -> client.delete(OBJECT_KEY));
        server.verify();
    }

    @Test
    @DisplayName("삭제 중 Supabase 4xx·5xx·타임아웃은 공통 오류로 바꾸고 객체 키·Secret Key를 예외 메시지에 남기지 않는다")
    void mapsDeleteFailuresToCommonError() {
        // given
        SupabaseProfileImageStorageClient client = client(properties(SECRET_KEY));
        server.expect(requestTo(DELETE_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"invalid " + OBJECT_KEY + "\"}"));
        server.expect(requestTo(DELETE_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(DELETE_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        // when & then
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.delete(OBJECT_KEY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
                        assertThat(e.getMessage())
                                .doesNotContain(SECRET_KEY)
                                .doesNotContain(OBJECT_KEY)
                                .doesNotContain(BASE_URL);
                    });
        }
        server.verify();
    }

    @Test
    @DisplayName("설정값이 없으면 삭제도 Supabase를 호출하지 않고 공통 오류를 던진다")
    void deleteNotConfigured() {
        // given
        SupabaseProfileImageStorageClient client = client(new SupabaseStorageProperties(
                "", " ", "drop-images", "seller-documents", BUCKET, Duration.ofSeconds(3), Duration.ofSeconds(5)));

        // when & then
        assertThatThrownBy(() -> client.delete(OBJECT_KEY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_SERVICE_ERROR));
        server.verify();
    }

    private static SupabaseStorageProperties properties(String secretKey) {
        return new SupabaseStorageProperties(BASE_URL, secretKey, "drop-images", "seller-documents", BUCKET,
                Duration.ofSeconds(3), Duration.ofSeconds(5));
    }
}
