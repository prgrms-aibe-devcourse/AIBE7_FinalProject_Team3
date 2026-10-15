package org.example.grab.domain.drop.stream.transport;

import org.example.grab.domain.drop.stream.model.StockChange;
import org.example.grab.domain.drop.stream.service.StockStreamRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0",
        "grab.stock-stream.coalesce-window=100ms", "grab.stock-stream.heartbeat-interval=100ms",
        "grab.stock-stream.connection-lifetime=2s", "grab.stock-stream.send-timeout=500ms",
        "grab.stock-stream.max-connections=4", "grab.stock-stream.max-connections-per-ip=3"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockStreamHttpIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StockStreamHub hub;
    @Autowired
    private StockStreamRegistry registry;

    private Long userId;
    private Long sellerId;
    private Long categoryId;
    private Long first;
    private Long second;
    private final List<Stream> streams = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        await(() -> registry.countConnections() == 0);
        String unique = UUID.randomUUID().toString();
        userId = jdbc.queryForObject("INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded', 'SSE') RETURNING id",
                Long.class, unique + "@example.com");
        sellerId = jdbc.queryForObject("INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, 'SSE', ?) RETURNING id",
                Long.class, userId, unique + "@example.com");
        categoryId = jdbc.queryForObject("INSERT INTO categories (code, name) VALUES (?, 'SSE') RETURNING id", Long.class, unique);
        first = insertDrop("WISH");
        second = insertDrop("WISH");
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Stream stream : streams) {
            stream.close();
        }
        await(() -> registry.countConnections() == 0);
        jdbc.update("DELETE FROM drops WHERE seller_id = ?", sellerId);
        jdbc.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
    }

    @Test
    @DisplayName("실제 Tomcat에서 ready·heartbeat를 flush하고 수명 종료 안내 후 자원을 정리한다")
    void streamsReadyHeartbeatAndLifetimeClosure() throws Exception {
        // when
        Stream stream = open(first.toString());

        // then
        assertThat(stream.connection.getResponseCode()).isEqualTo(200);
        assertThat(stream.connection.getContentType()).startsWith("text/event-stream");
        assertThat(stream.connection.getHeaderField("Cache-Control")).contains("no-cache", "no-store");
        assertThat(stream.next()).contains("event:ready", "\"dropIds\":[" + first + "]", "serverTime").doesNotContain("success", "id:", "retry:");
        assertThat(stream.next()).contains(": heartbeat");
        String frame;
        do {
            frame = stream.next();
        } while (!frame.contains("stream-closed"));
        assertThat(frame).contains("RECONNECT_REQUIRED", "\"reconnect\":true");
        await(() -> registry.countConnections() == 0);
    }

    @Test
    @DisplayName("두 독립 HTTP 연결과 다중 구독에 병합 변경을 전달하고 다른 상품 구독은 제외한다")
    void deliversChangesOnlyToMatchingSubscribers() throws Exception {
        // given
        Stream a = open(first.toString());
        Stream b = open(second + "," + first);
        Stream unrelated = open(second.toString());
        assertThat(a.next()).contains("ready");
        assertThat(b.next()).contains("ready");
        assertThat(unrelated.next()).contains("ready");

        // when
        hub.publish(first, Set.of(StockChange.STOCK));
        hub.publish(first, Set.of(StockChange.SALES));

        // then
        assertThat(a.nextChange()).contains("\"dropId\":" + first, "STOCK", "SALES").doesNotContain("availableStock", "orderId");
        assertThat(b.nextChange()).contains("\"dropId\":" + first, "STOCK", "SALES");
        assertThat(unrelated.next()).contains(": heartbeat").doesNotContain("drop-changed");
    }

    @Test
    @DisplayName("실제 HTTP 쿼리 오류·DRAFT 혼합·무효 쿠키는 스트림 대신 공통 JSON 오류다")
    void returnsJsonBeforeStreaming() throws Exception {
        // given
        Long draft = insertDrop("DRAFT");

        // when & then
        assertError("?dropIds=1,,2", null, 400, "INVALID_REQUEST");
        assertError("?dropIds=" + first + "&dropIds=" + second, null, 400, "INVALID_REQUEST");
        assertError("?dropIds=" + first + "," + draft, null, 404, "DROP_NOT_FOUND");
        assertError("?dropIds=" + first, "access_token=invalid", 401, "INVALID_TOKEN");
        assertThat(registry.countConnections()).isZero();
    }

    @Test
    @DisplayName("IP 연결 상한 초과는 JSON 429와 Retry-After를 반환한다")
    void returnsRetryAfterAtIpLimit() throws Exception {
        // given
        for (int i = 0; i < 3; i++) {
            assertThat(open(first.toString()).next()).contains("ready");
        }
        // when & then
        HttpURLConnection connection = request("?dropIds=" + first, null);
        try {
            assertThat(connection.getResponseCode()).isEqualTo(429);
            assertThat(connection.getHeaderField("Retry-After")).isEqualTo("60");
            assertThat(new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("STOCK_STREAM_LIMIT_EXCEEDED");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    @DisplayName("기존 구독 상품이 비공개가 되면 변경을 전송하지 않고 재연결 금지 종료를 보낸다")
    void invalidatesPrivateSubscription() throws Exception {
        // given: 테스트 DB에서만 상태를 바꿔 향후 비공개 전이를 재현한다. 운영용 API를 추가하지 않는다.
        Stream stream = open(first.toString());
        assertThat(stream.next()).contains("ready");
        jdbc.update("UPDATE drops SET status='DRAFT' WHERE id=?", first);

        // when
        hub.publish(first, Set.of(StockChange.STATUS));

        // then
        String frame;
        do {
            frame = stream.next();
            assertThat(frame).doesNotContain("drop-changed");
        } while (!frame.contains("stream-closed"));
        assertThat(frame).contains("SUBSCRIPTION_INVALIDATED", "\"reconnect\":false");
    }

    @Test
    @DisplayName("전체 연결 상한 초과는 JSON 503과 Retry-After이며 다른 IP 슬롯도 보존한다")
    void returnsRetryAfterAtServerLimit() throws Exception {
        // given: 실제 remoteAddr를 조작하지 않고 다른 IP의 예약 슬롯으로 전체 용량을 채운다.
        var held = new ArrayList<org.example.grab.domain.drop.stream.model.StockStreamSubscription>();
        try {
            for (int i = 0; i < 4; i++) {
                held.add(registry.register("other-" + i, List.of(first)));
            }
            // when & then
            HttpURLConnection connection = request("?dropIds=" + first, null);
            try {
                assertThat(connection.getResponseCode()).isEqualTo(503);
                assertThat(connection.getHeaderField("Retry-After")).isEqualTo("60");
                assertThat(new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8))
                        .contains("STOCK_STREAM_UNAVAILABLE");
                assertThat(registry.countConnections()).isEqualTo(4);
            } finally {
                connection.disconnect();
            }
        } finally {
            held.forEach(subscription -> registry.unregister(subscription.id()));
        }
    }

    @Test
    @DisplayName("클라이언트가 연결·단절을 반복해도 구독 인덱스와 전송 자원이 누적되지 않는다")
    void cleansUpRepeatedDisconnects() throws Exception {
        // when & then
        for (int i = 0; i < 5; i++) {
            Stream stream = open(first.toString());
            assertThat(stream.next()).contains("ready");
            stream.close();
            await(() -> registry.countConnections() == 0);
            assertThat(registry.findSubscribers(first)).isEmpty();
        }
    }

    private Stream open(String ids) throws Exception {
        HttpURLConnection connection = request("?dropIds=" + ids, null);
        assertThat(connection.getResponseCode()).isEqualTo(200);
        Stream stream = new Stream(connection, new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)));
        streams.add(stream);
        return stream;
    }

    private HttpURLConnection request(String query, String cookie) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://localhost:" + port + "/api/v1/drops/stock-stream" + query).toURL().openConnection();
        connection.setRequestProperty("Accept", "text/event-stream");
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        return connection;
    }

    private void assertError(String query, String cookie, int status, String code) throws Exception {
        HttpURLConnection connection = request(query, cookie);
        try {
            assertThat(connection.getResponseCode()).isEqualTo(status);
            assertThat(connection.getContentType()).startsWith("application/json");
            assertThat(new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)).contains(code).doesNotContain("event:ready");
        } finally {
            connection.disconnect();
        }
    }

    private Long insertDrop(String status) {
        return jdbc.queryForObject("""
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                    sale_starts_at, sale_ends_at, published_at)
                VALUES (?, ?, ?, 'SSE', '설명', 0, '배송', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, sellerId, categoryId, status);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private record Stream(HttpURLConnection connection, BufferedReader reader) implements AutoCloseable {
        String next() throws Exception {
            StringBuilder frame = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() && !frame.isEmpty()) {
                    return frame.toString();
                }
                frame.append(line).append('\n');
            }
            throw new AssertionError("SSE frame 전에 연결이 종료됐습니다: " + frame);
        }

        String nextChange() throws Exception {
            String frame;
            do {
                frame = next();
            } while (frame.contains(": heartbeat"));
            return frame;
        }

        @Override
        public void close() throws Exception {
            connection.disconnect();
            reader.close();
        }
    }
}
