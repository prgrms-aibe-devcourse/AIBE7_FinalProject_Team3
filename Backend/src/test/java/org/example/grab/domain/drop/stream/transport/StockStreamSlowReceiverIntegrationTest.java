package org.example.grab.domain.drop.stream.transport;

import org.example.grab.domain.drop.stream.model.StockChange;
import org.example.grab.domain.drop.stream.config.StockStreamExecutors;
import org.example.grab.domain.drop.stream.service.StockStreamRegistry;
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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0",
        "grab.stock-stream.coalesce-window=1ms", "grab.stock-stream.heartbeat-interval=100ms",
        "grab.stock-stream.connection-lifetime=30s", "grab.stock-stream.send-timeout=30s"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockStreamSlowReceiverIntegrationTest {

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
    @Autowired
    private StockStreamExecutors executors;

    @Test
    @DisplayName("실제 TCP 수신을 멈춰도 비차단 SSE가 전송 스레드를 붙잡지 않고 연결을 정리한다")
    void releasesSlowTcpReceiverBeforeConnectionLifetime() throws Exception {
        // given: 컨테이너 DB·실제 Tomcat 소켓에서만 재현한다. 수명 30초와 별개로 정체를 검증한다.
        String unique = UUID.randomUUID().toString();
        Long user = jdbc.queryForObject("INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded', 'SSE') RETURNING id", Long.class, unique + "@example.com");
        Long seller = jdbc.queryForObject("INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, 'SSE', ?) RETURNING id", Long.class, user, unique + "@example.com");
        Long category = jdbc.queryForObject("INSERT INTO categories (code, name) VALUES (?, 'SSE') RETURNING id", Long.class, unique);
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                    sale_starts_at, sale_ends_at, published_at)
                SELECT ?, ?, 'WISH', 'SSE', '설명', 0, '배송', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP FROM generate_series(1,100)
                RETURNING id
                """, Long.class, seller, category);
        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(1024);
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            String request = "GET /api/v1/drops/stock-stream?dropIds=" + ids.stream().map(Object::toString).collect(Collectors.joining(","))
                    + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            do {
                line = reader.readLine();
                assertThat(line).isNotNull();
            } while (!line.contains("event:ready"));
            assertThat(registry.countConnections()).isEqualTo(1);

            // when: ready 이후 읽기를 멈추고 다중 상품 변경을 공급해 TCP 송신 버퍼를 채운다.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (registry.countConnections() > 0 && System.nanoTime() < until) {
                for (Long id : ids) {
                    hub.publish(id, Set.of(StockChange.STOCK));
                }
                hub.tick();
                Thread.sleep(1);
            }

            // then: 30초 수명 만료를 기다리지 않고 슬롯·전송 작업이 정리된다.
            assertThat(registry.countConnections()).isZero();
            while (executors.sender().getActiveCount() > 0 && System.nanoTime() < until) {
                Thread.sleep(2);
            }
            assertThat(executors.sender().getActiveCount()).isZero();
            assertThat(executors.sender().getQueue()).isEmpty();
        } finally {
            for (var subscription : registry.findSubscribers(ids.get(0))) {
                hub.disconnect(subscription.id());
            }
            jdbc.update("DELETE FROM drops WHERE seller_id=?", seller);
            jdbc.update("DELETE FROM sellers WHERE id=?", seller);
            jdbc.update("DELETE FROM users WHERE id=?", user);
            jdbc.update("DELETE FROM categories WHERE id=?", category);
        }
    }
}
