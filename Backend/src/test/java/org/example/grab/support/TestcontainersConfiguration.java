package org.example.grab.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
    DB 통합 테스트용 공통 PostgreSQL 컨테이너(GR-67).
    JVM당 컨테이너 하나를 정적으로 띄워 모든 테스트가 재사용한다. @ServiceConnection이
    spring.datasource.* 기본값(localhost:5432/grab)을 이 컨테이너 연결 정보로 덮어쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // pgvector 확장(GR-147, drop_embeddings)이 들어 있는 PostgreSQL 18 이미지. 운영 Supabase의 vector 0.8.2와 버전을 맞춘다.
    // 클래스 전용 컨테이너를 띄우는 테스트도 이 이미지를 써야 마이그레이션(CREATE EXTENSION vector)이 통과한다
    public static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg18-trixie").asCompatibleSubstituteFor("postgres");

    /*
        Spring은 설정이 다른 테스트 컨텍스트를 닫지 않고 캐시하며, 컨텍스트마다 Hikari 풀이 연결 10개를 유휴 상태로도 유지한다.
        클래스 전용 Redis 컨테이너(@DynamicPropertySource) 등으로 컨텍스트가 늘면 기본 max_connections(100)를 넘어
        "too many clients already"로 기동에 실패하므로 한도를 올린다(GR-34). 테스트 전용 컨테이너라 운영 설정과 무관하다.
     */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }
}
