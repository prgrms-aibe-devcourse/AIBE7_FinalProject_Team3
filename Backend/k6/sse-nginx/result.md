# SSE Nginx 경유 검증 결과 (GR-180)

> `REALTIME_STOCK.md` 13.3의 프록시 인계 항목을 로컬 Nginx로 확인했다. 운영 `deploy/nginx/grab.conf`의 location 블록을
> 그대로 쓰고 TLS만 뺐다(`nginx.sh`). 운영 Nginx는 EC2 호스트에 설치돼 있어 `nginx.conf`(워커 수·연결 수·파일 디스크립터)가
> 레포에 없으므로, Ubuntu 패키지 기본값을 흉내 낸 값과 올린 값을 각각 쟀다. 실행은 `verify.sh`, 클라이언트는 `reconnect.mjs`.

## 결론

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| 버퍼링 비활성 | **통과** | Nginx 경유와 직접 연결의 전달 지연 p95가 987 / 986ms로 같다([sse-stream/result-refetch.md](../sse-stream/result-refetch.md)) |
| heartbeat flush | **통과** | Nginx 경유 heartbeat 간격 p50 15.03초, 최대 15.06초. 모아 보내지 않는다 |
| 읽기 유휴 제한 45초 이상 | **통과** | heartbeat를 90초로 늦춰도 50개 연결이 150초 동안 끊기지 않았다(`proxy_read_timeout 1h`) |
| 최대 연결 수 (Ubuntu 기본값 흉내) | **미통과** | 워커 2 × `worker_connections 768`에서 1000개 중 **766개**만 연결. `768 worker_connections are not enough` |
| 최대 연결 수 (올린 값) | **통과** | `worker_connections 4096`, 파일 디스크립터 8192에서 1000개 전원 ready |
| reload(배포 전환) 중 연결 유지 | **통과** | reload 전후 300개 연결 끊김 0, heartbeat 간격 최대 15.06초. 이전 워커는 `shutting down`으로 남아 기존 연결을 계속 전달 |
| 종료 시 drain·재접속 | **통과(조건부: 단일 backend 재시작)** | backend 종료 시 300개 전원이 `stream-closed`(RECONNECT_REQUIRED)를 받고 전원 재접속. 단일 backend라 재기동 중 502가 나서 복구 p50 13.4초 |

**운영 조치 필요:** SSE 하나가 Nginx 연결 2개(클라이언트·업스트림)를 쓰므로 기본값(워커당 768)이면 SSE 약 760개에서 막힌다.
운영 EC2의 `/etc/nginx/nginx.conf`에서 `worker_connections`를 확인하고, 1000 연결을 받으려면 4096 이상과 `worker_rlimit_nofile`
(또는 systemd `LimitNOFILE`)을 함께 올려야 한다. 이 값은 레포 밖이라 이 이슈에서 바꾸지 않았다.

## 측정 조건

- 실행일 2026-10-28, 커밋 `perf/GR-180-sse-refetch-load`(GR-183 + GR-186 머지).
- Nginx `nginx:1.24-alpine`(Ubuntu 24.04 패키지 버전), `worker_processes 2`(t3.small vCPU 2개), `access_log off`.
- backend는 `compose.loadtest-micro.yaml` + `compose.loadtest-sse.yaml` + `compose.loadtest-db-nano.yaml`, netem 2.5ms.
  재접속이 몰려도 구독 요청 제한에 먼저 걸리지 않게 `GRAB_STOCKSTREAM_CONNECTRATEMAX=100000`으로 띄웠다(검증 대상 아님).
- 클라이언트는 같은 Mac의 Node(`reconnect.mjs`). 재접속 지연은 12절(정상 종료 5초 + 0~5초, 실패 지수 백오프)을 따른다.

## 항목별 결과

| 회차 | 연결 | 유지 | 결과 |
| --- | --- | --- | --- |
| idle (heartbeat 90초) | 50 | 150초 | 끝까지 50개 유지, 종료 0, heartbeat 간격 90.04초 |
| limit (768, nofile 1024) | 1000 | 60초 | 첫 시도 200 응답 766개, 나머지 234개는 소켓 오류 후 재시도 1,116회 전부 실패. 끝까지 766개 |
| limit-up (4096, nofile 8192) | 1000 | 60초 | 1000개 전원 ready, Nginx 연결 부족 로그 0 |
| reload | 300 | 120초 | 40초에 reload. 5초 뒤·35초 뒤 모두 이전 워커 2개가 `shutting down`, 새 워커 2개. 연결 종료 0 |
| restart | 300 | 180초 | 40초에 backend 종료(`docker compose stop`) 후 시작. 종료 안내 300건, 재기동 중 502 557건·503 1건, 300개 전원 재접속(복구 p50 13.4 / p95 17.1 / 최대 18.2초) |

- **reload와 이전 워커:** `worker_shutdown_timeout`이 없어 이전 워커는 자기 연결이 모두 끝날 때까지 남는다. SSE 연결은 서버 수명(10분)이나
  배포 스크립트의 이전 컬러 종료(전환 30초 뒤)로 끝나므로 이전 워커도 그 안에 정리된다. 정리되기 전까지는 워커 수만큼 연결 한도를 나눠 쓴다.
- **restart 복구 시간:** 단일 backend를 껐다 켰으므로 재기동(약 10초) 동안 Nginx가 502를 돌려주고, 클라이언트는 실패로 보고 백오프한다.
  운영 blue/green은 새 컬러가 이미 떠 있는 상태에서 이전 컬러를 끄므로 502 없이 정상 종료 지연(5~10초) 안에 재접속할 것으로 보인다(추정, 미측정).
  두 경우 모두 13.1의 "SSE 실패 후 대체 조회 수렴 25초" 안이다.
- **reload 첫 회차 이상치:** 처음 실행한 reload 회차는 300개 연결이 120초 동안 heartbeat를 하나도 받지 못했다(연결 종료는 0).
  같은 절차로 다시 실행한 회차는 정상(간격 15.03초)이었고 앞뒤 회차도 정상이라 재현하지 못했다. 원인은 확인하지 못했다(`out/reload-1st.json`).

## 한계

- 운영 Nginx의 실제 `nginx.conf`·systemd 한도를 보지 못해 기본값을 가정했다. 운영 값으로 다시 재야 확정된다.
- TLS를 뺐다. 운영은 443에서 TLS를 종료하므로 연결당 메모리·CPU가 더 든다.
- blue/green 두 backend 사이 전환은 재현하지 않았다(backend 하나를 껐다 켬).
- 클라이언트·Nginx·backend가 같은 Mac에 있어 네트워크 지연이 없다.
