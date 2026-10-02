package org.example.grab.global.storage.supabase;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseStorageClientTest {

    private static final String BASE_URL = "https://project.supabase.co";
    private static final String SERVICE_ROLE_KEY = "service-role-secret";
    private static final String BUCKET = "drop-images";
    private static final String OBJECT_KEY = "images/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.jpg";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    @DisplayName("서명 요청을 올바른 경로·Bearer 인증으로 보내고 uploadUrl·imageUrl을 조립한다")
    void createSignedUploadUrl() {
        // given
        SupabaseStorageClient client = new SupabaseStorageClient(builder, properties(SERVICE_ROLE_KEY));
        server.expect(requestTo(BASE_URL + "/storage/v1/object/upload/sign/" + BUCKET + "/" + OBJECT_KEY))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + SERVICE_ROLE_KEY))
                .andRespond(withSuccess(
                        "{\"url\":\"/object/upload/sign/" + BUCKET + "/" + OBJECT_KEY + "?token=signed-token\"}",
                        MediaType.APPLICATION_JSON));

        // when
        SignedUploadUrl result = client.createSignedUploadUrl(OBJECT_KEY);

        // then
        server.verify();
        assertThat(result.uploadUrl()).isEqualTo(
                BASE_URL + "/storage/v1/object/upload/sign/" + BUCKET + "/" + OBJECT_KEY + "?token=signed-token");
        assertThat(result.imageUrl()).isEqualTo(
                BASE_URL + "/storage/v1/object/public/" + BUCKET + "/" + OBJECT_KEY);
    }

    @Test
    @DisplayName("Supabase 4xx·5xx·타임아웃은 공통 오류로 바꾸고 키·토큰을 예외 메시지에 남기지 않는다")
    void mapsFailuresToCommonError() {
        // given
        SupabaseStorageClient client = new SupabaseStorageClient(builder, properties(SERVICE_ROLE_KEY));
        server.expect(requestTo(BASE_URL + "/storage/v1/object/upload/sign/" + BUCKET + "/" + OBJECT_KEY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"invalid\"}"));
        server.expect(requestTo(BASE_URL + "/storage/v1/object/upload/sign/" + BUCKET + "/" + OBJECT_KEY))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(BASE_URL + "/storage/v1/object/upload/sign/" + BUCKET + "/" + OBJECT_KEY))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        // when & then
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.createSignedUploadUrl(OBJECT_KEY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException businessException = (BusinessException) e;
                        assertThat(businessException.getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
                        assertThat(businessException.getMessage())
                                .doesNotContain(SERVICE_ROLE_KEY)
                                .doesNotContain("signed-token");
                    });
        }
        server.verify();
    }

    @Test
    @DisplayName("응답에 서명 URL이 없으면 공통 오류로 바꾼다")
    void rejectsMissingSignedUrl() {
        // given
        SupabaseStorageClient client = new SupabaseStorageClient(builder, properties(SERVICE_ROLE_KEY));
        server.expect(requestTo(BASE_URL + "/storage/v1/object/upload/sign/" + BUCKET + "/" + OBJECT_KEY))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // when & then
        assertThatThrownBy(() -> client.createSignedUploadUrl(OBJECT_KEY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_SERVICE_ERROR));
    }

    @Test
    @DisplayName("설정값이 없으면 Supabase를 호출하지 않고 공통 오류를 던진다")
    void notConfigured() {
        // given
        SupabaseStorageClient client = new SupabaseStorageClient(builder,
                new SupabaseStorageProperties("", " ", BUCKET, Duration.ofSeconds(3), Duration.ofSeconds(5)));

        // when & then
        assertThatThrownBy(() -> client.createSignedUploadUrl(OBJECT_KEY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_SERVICE_ERROR));
        server.verify();
    }

    private static SupabaseStorageProperties properties(String serviceRoleKey) {
        return new SupabaseStorageProperties(BASE_URL, serviceRoleKey, BUCKET, Duration.ofSeconds(3), Duration.ofSeconds(5));
    }
}
