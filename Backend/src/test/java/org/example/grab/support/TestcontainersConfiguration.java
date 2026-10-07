package org.example.grab.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/*
    DB 통합 테스트용 공통 PostgreSQL 컨테이너(GR-67).
    JVM당 컨테이너 하나를 정적으로 띄워 모든 테스트가 재사용한다. @ServiceConnection이
    spring.datasource.* 기본값(localhost:5432/grab)을 이 컨테이너 연결 정보로 덮어쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }
}
