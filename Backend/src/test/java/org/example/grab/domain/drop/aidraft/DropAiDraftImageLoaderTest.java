package org.example.grab.domain.drop.aidraft;

import com.sun.net.httpserver.HttpServer;
import org.example.grab.domain.drop.aidraft.DropAiDraftClient.DraftImage;
import org.example.grab.global.storage.ImageStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class DropAiDraftImageLoaderTest {

    private static final UUID IMAGE_ID = UUID.fromString("7c0d3a1e-1b2f-4c55-9d1e-2a4b6c8d0e1f");

    private HttpServer server;
    private String baseUrl;
    private DropAiDraftImageLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, new byte[]{1, 2, 3}));
        server.createContext("/missing", exchange -> respond(exchange, 404, new byte[0]));
        server.createContext("/large", exchange ->
                respond(exchange, 200, new byte[DropAiDraftImageLoader.MAX_IMAGE_BYTES + 1]));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/ok");
            respond(exchange, 302, new byte[0]);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ImageStorage imageStorage = mock(ImageStorage.class);
        given(imageStorage.publicUrl(anyString())).willAnswer(invocation -> baseUrl + "/" + invocation.getArgument(0));
        loader = new DropAiDraftImageLoader(imageStorage,
                new DropAiDraftProperties(Duration.ofSeconds(20), Duration.ofSeconds(19), Duration.ofSeconds(2), 20, 1024, 0));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("imageId로 다시 만든 공개 URL과 같을 때만 확장자의 형식을 돌려주고 다른 주소는 받지 않는다")
    void contentTypeOf_acceptsOnlyIssuedUrl() {
        assertThat(loader.contentTypeOf(IMAGE_ID, baseUrl + "/images/" + IMAGE_ID + ".png")).contains("image/png");
        assertThat(loader.contentTypeOf(IMAGE_ID, baseUrl + "/images/" + IMAGE_ID + ".gif")).isEmpty();
        assertThat(loader.contentTypeOf(IMAGE_ID, "http://169.254.169.254/latest/meta-data")).isEmpty();
        assertThat(loader.contentTypeOf(UUID.randomUUID(), baseUrl + "/images/" + IMAGE_ID + ".png")).isEmpty();
    }

    @Test
    @DisplayName("200 응답 본문을 읽고, 실패 상태·5MB 초과·리다이렉트는 IOException으로 알린다")
    void load_readsBodyWithinLimit() throws IOException {
        DraftImage image = loader.load(baseUrl + "/ok", "image/jpeg");

        assertThat(image.data()).containsExactly(1, 2, 3);
        assertThat(image.contentType()).isEqualTo("image/jpeg");
        assertThatThrownBy(() -> loader.load(baseUrl + "/missing", "image/jpeg")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> loader.load(baseUrl + "/large", "image/jpeg")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> loader.load(baseUrl + "/redirect", "image/jpeg")).isInstanceOf(IOException.class);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
