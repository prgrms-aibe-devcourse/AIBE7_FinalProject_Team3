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
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseDocumentStorageClientTest {

    private static final String BASE_URL = "https://project.supabase.co";
    private static final String SECRET_KEY = "sb_secret_test";
    private static final String DOCUMENTS_BUCKET = "seller-documents";
    private static final String OBJECT_KEY = "b1f2c3d4-5678-4abc-9012-3456789abcde";
    private static final byte[] CIPHERTEXT = "암호화된 바이트".getBytes(StandardCharsets.UTF_8);

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    @DisplayName("암호문 바이트를 서류 버킷 경로로 octet-stream 본문·인증 헤더와 함께 업로드한다")
    void storesCiphertextToDocumentsBucket() {
        // given
        SupabaseDocumentStorageClient client = new SupabaseDocumentStorageClient(builder, properties(SECRET_KEY));
        server.expect(requestTo(BASE_URL + "/storage/v1/object/" + DOCUMENTS_BUCKET + "/" + OBJECT_KEY))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", SECRET_KEY))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(CIPHERTEXT))
                .andRespond(withSuccess());

        // when
        client.store(OBJECT_KEY, CIPHERTEXT);

        // then
        server.verify();
    }

    @Test
    @DisplayName("Supabase 4xx·5xx·타임아웃은 공통 오류로 바꾸고 Secret Key를 예외 메시지에 남기지 않는다")
    void mapsFailuresToCommonError() {
        // given
        SupabaseDocumentStorageClient client = new SupabaseDocumentStorageClient(builder, properties(SECRET_KEY));
        server.expect(requestTo(BASE_URL + "/storage/v1/object/" + DOCUMENTS_BUCKET + "/" + OBJECT_KEY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(BASE_URL + "/storage/v1/object/" + DOCUMENTS_BUCKET + "/" + OBJECT_KEY))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(BASE_URL + "/storage/v1/object/" + DOCUMENTS_BUCKET + "/" + OBJECT_KEY))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        // when & then
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.store(OBJECT_KEY, CIPHERTEXT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException businessException = (BusinessException) e;
                        org.assertj.core.api.Assertions.assertThat(businessException.getErrorCode())
                                .isEqualTo(CommonErrorCode.EXTERNAL_SERVICE_ERROR);
                        org.assertj.core.api.Assertions.assertThat(businessException.getMessage())
                                .doesNotContain(SECRET_KEY);
                    });
        }
        server.verify();
    }

    @Test
    @DisplayName("설정값이 없으면 Supabase를 호출하지 않고 공통 오류를 던진다")
    void notConfigured() {
        // given
        SupabaseDocumentStorageClient client = new SupabaseDocumentStorageClient(builder,
                new SupabaseStorageProperties("", " ", "drop-images", DOCUMENTS_BUCKET,
                        Duration.ofSeconds(3), Duration.ofSeconds(5)));

        // when & then
        assertThatThrownBy(() -> client.store(OBJECT_KEY, CIPHERTEXT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                .isEqualTo(CommonErrorCode.EXTERNAL_SERVICE_ERROR));
        server.verify();
    }

    private static SupabaseStorageProperties properties(String secretKey) {
        return new SupabaseStorageProperties(BASE_URL, secretKey, "drop-images", DOCUMENTS_BUCKET,
                Duration.ofSeconds(3), Duration.ofSeconds(5));
    }
}
