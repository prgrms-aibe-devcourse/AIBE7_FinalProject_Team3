package org.example.grab.domain.drop.aidraft;

import org.example.grab.domain.drop.aidraft.DropAiDraftClient.DraftImage;
import org.example.grab.global.storage.ImageStorage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/*
    초안에 쓸 업로드 이미지를 내려받는다(DROP.md 2.8).
    imageUrl은 imageId로 다시 만든 공개 이미지 URL과 같을 때만 받는다(DROP 저장과 같은 규칙, DropService).
    그래서 서버가 판매자가 보낸 임의의 주소로 요청하지 않는다(서버 측 요청 위조 방지). 리다이렉트도 따라가지 않는다.
    형식은 응답 헤더가 아니라 발급할 때 정한 확장자로 정하고, 업로드 제한(5MB)을 넘는 본문은 읽지 않는다.
 */
@Component
public class DropAiDraftImageLoader {

    static final String IMAGE_OBJECT_KEY_PREFIX = "images/";
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

    private final ImageStorage imageStorage;
    private final DropAiDraftProperties properties;
    private final HttpClient httpClient;

    public DropAiDraftImageLoader(ImageStorage imageStorage, DropAiDraftProperties properties) {
        this.imageStorage = imageStorage;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.imageDownloadTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // imageId의 공개 URL이면 그 확장자의 MIME 형식을, 아니면 빈 값을 돌려준다
    public Optional<String> contentTypeOf(UUID imageId, String imageUrl) {
        return CONTENT_TYPES.entrySet().stream()
                .filter(entry -> imageStorage.publicUrl(IMAGE_OBJECT_KEY_PREFIX + imageId + "." + entry.getKey())
                        .equals(imageUrl))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    // 확인을 마친 URL만 넘긴다. 실패하면 IOException으로 알리고 호출 측이 AI_DRAFT_FAILED로 바꾼다
    public DraftImage load(String verifiedImageUrl, String contentType) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(verifiedImageUrl))
                .timeout(properties.imageDownloadTimeout())
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("이미지 다운로드가 중단되었습니다.", e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("이미지 다운로드 실패: status=" + response.statusCode());
            }
            byte[] data = body.readNBytes(MAX_IMAGE_BYTES + 1);
            if (data.length == 0 || data.length > MAX_IMAGE_BYTES) {
                throw new IOException("이미지 크기가 허용 범위를 벗어났습니다.");
            }
            return new DraftImage(data, contentType);
        }
    }
}
