package org.example.grab.domain.drop.stream.transport;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StockStreamAsyncInterceptor implements AsyncHandlerInterceptor {

    public static final String SUBSCRIPTION_ATTRIBUTE = StockStreamAsyncInterceptor.class.getName() + ".subscription";
    private final StockStreamHub hub;

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID id = (UUID) request.getAttribute(SUBSCRIPTION_ATTRIBUTE);
        if (id == null) {
            return;
        }
        try {
            ServletOutputStream output = response.getOutputStream();
            // MVC가 async 처리를 시작한 뒤 Servlet 비차단 모드로 전환한다. 다른 API의 소켓 설정은 바꾸지 않는다.
            output.setWriteListener(new WriteListener() {
                @Override
                public void onWritePossible() {
                    // 최초 쓰기 가능 통지 뒤에 활성화해 listener 등록 중의 일시적 not-ready를 실패로 처리하지 않는다.
                    hub.activate(id, output::isReady);
                }

                @Override
                public void onError(Throwable error) {
                    hub.disconnect(id);
                }
            });
        } catch (IOException | RuntimeException error) {
            // 이미 async 응답이 시작됐으므로 JSON 오류를 덧쓰지 않고 자원을 반환한다.
            hub.disconnect(id);
        }
    }
}
