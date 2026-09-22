# 로컬 메트릭 모니터링

## 1. 구성

| 구성 요소 | 역할 | 주소 |
| --- | --- | --- |
| Spring Boot Actuator | 애플리케이션 메트릭 노출 | `http://localhost:8080/actuator/prometheus` |
| Prometheus | 15초마다 메트릭 수집·저장 | `http://localhost:9090` |

Prometheus는 Docker Compose 내부 DNS를 통해 `backend:8080`의 `/actuator/prometheus`를 수집한다.

## 2. 실행

`Backend` 디렉터리에서 다음 명령을 실행한다.

```bash
docker compose up -d --build backend prometheus
docker compose ps
```

Prometheus 설정을 변경했다면 컨테이너를 재생성한다.

```bash
docker compose up -d --force-recreate prometheus
```

## 3. Actuator 확인

상태 엔드포인트:

```bash
curl http://localhost:8080/actuator/health
```

Prometheus 메트릭 엔드포인트:

```bash
curl http://localhost:8080/actuator/prometheus
```

두 요청이 HTTP 200으로 응답해야 한다. 메트릭 응답에서는 `jvm_`, `http_server_requests_`, `hikaricp_` 접두사를 확인한다.

## 4. Prometheus 타깃 확인

브라우저에서 `http://localhost:9090/targets`를 연다. 다음 타깃이 표시되어야 한다.

| Job | Endpoint | State |
| --- | --- | --- |
| `backend` | `http://backend:8080/actuator/prometheus` | `UP` |
| `prometheus` | `http://localhost:9090/metrics` | `UP` |

첫 수집까지 최대 15초가 걸릴 수 있다.

## 5. 메트릭 조회

Prometheus의 `http://localhost:9090/query`에서 다음 PromQL을 실행한다.

```promql
up{job="backend"}
jvm_memory_used_bytes{job="backend"}
http_server_requests_seconds_count{job="backend"}
hikaricp_connections{job="backend"}
```

`up{job="backend"}`의 값은 `1`이어야 하며 나머지 쿼리는 하나 이상의 시계열을 반환해야 한다.

## 6. 설정 및 로그 확인

Prometheus 설정 검증:

```bash
docker compose exec prometheus promtool check config /etc/prometheus/prometheus.yml
```

백엔드와 Prometheus 로그 확인:

```bash
docker compose logs --tail 100 backend prometheus
```

타깃이 `DOWN`이면 백엔드 컨테이너 상태, `/actuator/prometheus` 응답, Prometheus 타깃의 `lastError` 순서로 확인한다.

## 7. 종료 및 데이터

```bash
docker compose down
```

수집된 메트릭은 `prometheus-data` 볼륨에 저장되므로 일반적인 `docker compose down` 이후에도 유지된다. `docker compose down -v`는 PostgreSQL을 포함한 모든 Compose 볼륨을 삭제하므로 전체 로컬 데이터를 초기화할 때만 사용한다.
