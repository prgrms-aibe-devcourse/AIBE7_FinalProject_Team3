# GRAB 기술 스택

> 상태: MVP 구현 기준 초안
> 원칙: 문제를 먼저 정의하고, 해결에 필요한 기술만 단계적으로 도입한다.

## 1. Backend

| 구분 | 기술 | 버전 | 용도 및 선정 이유 |
| --- | --- | --- | --- |
| Language | Java | 17 | 팀의 학습 경험과 라이브러리 호환성이 안정적이며 Spring Boot 4.1의 최소 요구 버전을 충족한다. |
| Framework | Spring Boot | 4.1.x | 웹, 보안, 데이터 접근, 모니터링 환경을 일관되게 구성한다. |
| Web | Spring MVC | Boot 관리 | REST API 구현에 사용하며 블로킹 방식의 JPA 환경에 적합하다. SSE는 MVP 이후 필요할 때 추가한다. |
| ORM | Spring Data JPA / Hibernate | Boot 관리 | 주문·옵션·결제 도메인의 관계 매핑과 트랜잭션을 관리한다. 복잡한 조회는 DTO Projection 또는 별도 쿼리로 처리한다. |
| Security | Spring Security | Boot 관리 | HttpOnly 쿠키 기반 JWT 인증, CSRF 보호, Argon2id 비밀번호 해시, USER·SELLER·ADMIN 권한 및 리소스 소유권을 검증한다. |
| Validation | Jakarta Bean Validation | Boot 관리 | 요청 DTO와 상태별 필수값을 검증한다. |
| Mail | Spring Boot Starter Mail (`JavaMailSender`) | Boot 관리 | LOCAL 회원가입의 이메일 인증 코드를 SMTP로 발송한다. 발송 인터페이스 뒤에 두어 운영 발송 서비스를 설정으로 교체한다(4.3절). |
| Build | Gradle Wrapper | 8.14+ 또는 9.x | 로컬과 CI에서 동일한 빌드 도구 버전을 사용한다. |
| API Docs | SpringDoc OpenAPI / Swagger UI | 3.1.0 | Spring Boot 4 기반 API 명세를 자동 생성하고 프론트엔드와 공유한다. |
| Monitoring Endpoint | Spring Boot Actuator | Boot 관리 | 상태 확인, 메트릭 노출 및 배포 성공 여부를 검증한다. |
| Scheduling | Spring `@Scheduled` + PostgreSQL 인덱스 폴링 | Boot 관리 | DROP 상태(WISH→GRAB→ENDED) 자동 전환과 결제 대기 만료. 별도 스케줄러 인프라 없이 기존 DB·인덱스만 사용한다(1.1절, 1.2절). |

### 1.1 DROP 상태 자동 전환 선정 이유 (GR-18)

구매 가능 여부는 저장 상태가 아니라 **서버 시각과 판매 기간**으로 판정하고, 자동 전환은 저장 상태·조회·통계·후속 이벤트를 동기화하는 역할만 담당한다. 그래서 상태 전환의 지연이 주문 시작·종료에 영향을 주지 않는다.

| 방식 | 장점 | 현재 제외 이유 |
| --- | --- | --- |
| **PostgreSQL 10초 폴링** | 기존 DB·인덱스만 사용, 장애 후 자동 보정, 구조와 운영이 단순 | 저장 상태·알림 이벤트가 최대 주기(약 10초)만큼 늦음 |
| Redis ZSET | 서브초 탐색, DB 반복 조회 감소 | 등록 정합성·원자적 선점·유실 복구·보정 폴링을 직접 구현해야 함 |
| Quartz JDBC JobStore | 개별 예약 영속화, misfire, 클러스터 선점 | 상태 전환 하나에 의존성·11개 테이블·트랜잭션·테스트 인프라가 추가됨 |
| JobRunr | 비동기 작업·재시도·대시보드가 편리함 | 내부적으로 저장소를 폴링하며 현재는 백그라운드 작업 종류가 부족함 |
| Spring Batch | 대량 처리·재시작·처리 이력에 강함 | 소수 상태 전환에는 Job·Step·메타데이터가 과도함 |

선택 근거:

1. **구매 정확성과 전환 주기를 분리했다.** 10초 상태 지연이 주문 시작·종료에 영향을 주지 않는다.
2. **조회 부하가 사용자 수와 무관하다.** 기본적으로 10초마다 시작·종료 인덱스 조회 두 번이며 사용자 증가가 폴링 횟수를 늘리지 않는다.
3. **기존 인덱스로 전체 스캔을 피한다.** 비용은 전체 DROP 수보다 이번에 전환할 대상 수에 가깝다.
4. **PostgreSQL만으로 복구된다.** 서버 중단 중 놓친 대상도 다음 실행의 `sale_* <= now` 조건으로 다시 조회된다.
5. **수평 확장이 가능하다.** `FOR UPDATE SKIP LOCKED`로 여러 인스턴스가 서로 다른 DROP을 처리하고 상태 조건이 포함된 UPDATE를 멱등하게 만든다.

운영·고도화 기준:

- 잠금이 없는 일반 상황에서도 저장 상태와 상태 기반 통계는 최대 약 10초 늦고, 주문이 행을 계속 잠그면 더 늦어질 수 있다.
- 같은 시각에 많은 DROP이 전환되면 쓰기와 행 잠금이 몰릴 수 있다.
- 판매 시작 순간 인기 DROP은 주문의 `FOR SHARE` 때문에 전환 조회에서 반복해 건너뛰어져 저장 상태 전환이 10초보다 오래 늦어질 수 있다.
- 다음이 측정되면 배치 크기·부분 인덱스를 먼저 조정하고, 이후 Redis ZSET 가속 계층을 검토한다.
  - 후보 조회 지연이 지속적으로 증가
  - 한 주기의 전환 처리가 10초 안에 끝나지 않음
  - 같은 구간에 수천 개 이상의 DROP이 반복적으로 시작·종료
  - 저장 상태나 알림 이벤트에 서브초 SLA가 요구됨
- Redis를 추가하더라도 PostgreSQL을 원본으로 유지하고 Redis 전체 유실 후 DB에서 재구축할 수 있어야 한다.

### 1.2 결제 대기 만료 배치 선정 이유 (GR-22, GR-65)

결제 가능 여부는 저장 상태가 아니라 **서버 시각과 `payment_expires_at`** 기준으로 판정한다. 만료 배치는 마감이 지난 주문을 `EXPIRED`로 바꾸고 선점 재고를 가용 재고로 돌려주는 역할만 담당하므로, 배치 지연은 재고 반환 시점에만 영향을 준다. 1.1절과 같이 `@Scheduled` 10초 폴링을 쓰되, 만료는 주문·예약·재고를 함께 바꾸므로 처리 단위가 다르다.

| 방식 | 장점 | 현재 제외 이유 |
| --- | --- | --- |
| **주문별 트랜잭션 + `SKIP LOCKED` (10초 폴링)** | 결제 확정과 같은 주문 행 잠금·만료 코드를 재사용, 실패가 주문 단위로 격리됨 | 대상이 많으면 주문 수만큼 트랜잭션이 생김 |
| 한 문장 일괄 UPDATE (1.1절 방식) | 쿼리 수가 적음 | 주문·예약·옵션 재고 세 테이블을 함께 바꿔야 해 만료 로직이 SQL과 코드에 중복됨 |
| 조회 시점 만료(lazy) | 배치가 필요 없음 | 아무도 조회하지 않는 주문은 재고가 반환되지 않음 |
| Redis 키 만료 알림 | 마감 시각에 즉시 반응 | 알림 유실 시 재고가 반환되지 않고, Redis를 재고 판단 근거로 쓰게 됨 |

선택 근거:

1. **결제 확정과 같은 잠금 규칙을 따른다.** 결제 확정과 만료는 같은 주문 행을 잠그고 `PAYMENT_PENDING`과 마감을 다시 확인하므로 한 경로만 재고를 바꾼다.
2. **결제를 기다리지 않는다.** 결제 확정이 잠근 주문은 `SKIP LOCKED`로 건너뛰고 다음 실행에서 다시 조회한다. 후보 조회는 `(payment_expires_at, id)` 키셋이라 건너뛴 주문을 같은 실행에서 반복 조회하지 않는다.
3. **PostgreSQL만으로 복구된다.** 서버 중단 중 놓친 주문도 다음 실행의 `payment_expires_at <= now` 조건으로 다시 조회된다.
4. **수평 확장이 가능하다.** 여러 인스턴스가 동시에 실행돼도 잠금과 상태 조건 재확인으로 재고는 한 번만 반환된다.

운영·고도화 기준:

- 재고 반환은 마감 후 최대 약 10초 늦고, 대상이 많거나 결제가 주문을 잠그고 있으면 더 늦어질 수 있다. 그동안 반환 대기 수량은 가용 재고에 잡히지 않는다.
- 진행 중인 결제가 있어도 마감이 지나면 만료하므로, 마감 직전 승인은 결제 `SUCCEEDED`, 보정 `REQUIRED`로 남을 수 있다(PAY-004). 진행 중 결제 정리는 GR-65에서 다룬다.
- 한 주기의 만료 처리가 10초 안에 끝나지 않거나 후보 조회 지연이 계속 늘면 배치 크기·주기를 먼저 조정한다.

## 2. Database

| 구분 | 기술 | 도입 시점 | 용도 및 선정 이유 |
| --- | --- | --- | --- |
| RDBMS | PostgreSQL | MVP | 회원·DROP·주문·재고·결제 데이터를 저장하는 영속 원장이다. 트랜잭션, 행 잠금, 인덱스 및 향후 pgvector 확장이 가능하다. |
| In-memory Store | Redis | MVP | Refresh Token 해시와 이메일 인증 코드·가입 컨텍스트 해시를 TTL과 함께 저장한다. 조회 캐시는 병목 확인 후 도입하며 주문과 재고의 최종 원장으로 사용하지 않는다. |
| Migration | Flyway | MVP | ERD 변경 이력을 SQL 마이그레이션으로 관리하고 환경별 스키마를 일치시킨다. |

재고 정합성은 PostgreSQL 트랜잭션과 행 잠금으로 먼저 보장한다. Redis는 MVP에서 Refresh Token과 이메일 인증 임시 데이터(인증 코드, 가입 컨텍스트, 발송 제한 횟수) 저장에만 사용하고 조회 캐시는 부하 테스트로 병목이 확인된 후 도입한다.

## 3. Frontend

| 구분 | 기술 | 버전 | 용도 및 선정 이유 |
| --- | --- | --- | --- |
| Language | TypeScript | 5.x | API 요청·응답과 클라이언트 상태의 타입을 명확하게 관리한다. |
| Framework | React | 19.x | 컴포넌트 기반으로 소비자·판매자·관리자 화면을 구성한다. |
| Build Tool | Vite | 8.x | 빠른 개발 서버와 단순한 SPA 빌드 환경을 제공한다. |
| Styling | Tailwind CSS | 4.x | 짧은 MVP 기간에 일관된 반응형 UI를 구현한다. |
| HTTP Client | Axios | 1.x | Credential·CSRF 헤더, 공통 오류 처리 및 쿠키 기반 토큰 재발급 흐름을 구성한다. |
| Routing | React Router | 7.x | 소비자·판매자·관리자 라우팅과 권한별 화면을 분리한다. |
| Server State | TanStack Query | 5.x | API 데이터 캐싱, 재조회, 로딩·오류 상태를 관리한다. |
| Client State | React Context 또는 Zustand | 필요 시 확정 | 로그인 사용자 정보 등 작은 전역 상태만 관리한다. 서버 응답 데이터는 TanStack Query에서 관리한다. |

## 4. Infrastructure / Deployment

### 4.1 저장소 구성 및 운영 기준

- `Backend/Dockerfile`: Gradle `bootJar`를 실행하는 멀티 스테이지 빌드와 non-root 런타임 이미지
- `Backend/compose.yaml`: 백엔드, PostgreSQL, Redis, Prometheus, Grafana, Loki, Alloy 컨테이너 정의
- `.github/workflows/backend-ci.yml`: `main` 또는 `deploy` 대상 PR과 두 브랜치의 푸시에서 PostgreSQL 기반 테스트와 Docker 이미지 빌드 검증
- `.github/workflows/publish-backend.yml`: Backend 이미지를 빌드해 GHCR에 `latest`, 커밋 SHA 태그로 게시. 운영 기준은 `deploy` 푸시지만 현재 트리거는 `main`이므로 변경이 필요하다.

Actuator와 Prometheus의 로컬 메트릭 수집 연결은 완료됐다. Redis 연결 설정과 Refresh Token 저장소(생성·해시·TTL·저장·조회·삭제)는 구현됐으며, 로그인·재발급·로그아웃 API 연동은 구현 예정이다. Grafana·Loki·Alloy는 실행 틀만 마련된 상태다.

### 4.2 목표 운영 구성

| 구분 | 기술 | 용도 | 선정 이유 |
| --- | --- | --- | --- |
| Cloud | AWS | 인프라 환경 | EC2·RDS 등 운영 자원을 한 환경에서 관리한다. |
| Compute | EC2 | 애플리케이션 서버 | Docker 기반 배포와 서버 운영 구성을 직접 학습하고 제어할 수 있다. |
| Database | Amazon RDS for PostgreSQL | 운영 DB | 자동 백업, 복구 및 관리형 PostgreSQL 환경을 사용한다. |
| Storage | Supabase Storage | DROP 이미지 저장 | 서명 업로드 URL을 지원하고, 공개 버킷 URL로 별도 CDN 없이 조회하며, 버킷 단위로 MIME·크기 제한을 걸 수 있다. |
| Container | Docker | 애플리케이션 컨테이너화 | 개발·CI·운영 환경 차이를 줄인다. |
| Container Management | Docker Compose | 컨테이너 실행 | Kubernetes 없이 애플리케이션과 운영 도구를 단순하게 관리한다. |
| Reverse Proxy | Nginx | TLS 종료 및 트래픽 전환 | 외부 요청을 애플리케이션으로 전달하고 Blue/Green 포트 전환에 사용한다. |
| Container Registry | GHCR | Docker 이미지 저장 | GitHub Actions와 권한 및 배포 흐름을 연계한다. |
| Secrets | AWS Systems Manager Parameter Store | 환경변수·비밀값 관리 | DB 비밀번호, PG Secret Key, SMTP 계정 정보, Supabase Secret Key가 저장소 및 이미지에 포함되는 것을 방지한다. |
| DNS / TLS | Route 53 + ACM | 도메인·인증서 | 운영 서비스에 HTTPS를 적용한다. |

RDS를 기본 운영 DB로 사용하고, DROP 이미지 저장소는 Supabase Storage로 확정한다. 비용이나 운영 일정 때문에 Neon 등 다른 관리형 PostgreSQL을 검토할 수 있지만, 같은 역할의 서비스를 동시에 사용하지 않고 배포 전 하나로 확정한다.

### 4.3 이메일 발송

LOCAL 회원가입의 이메일 인증 코드(`MEMBER_AUTH.md` 1.2절)는 SMTP로 발송한다. 애플리케이션은 발송 인터페이스(`EmailSender`)에만 의존하고, 실제 발송 구현은 `app.mail.provider` 설정으로 선택한다.

| 환경 | 발송 대상 | 용도 |
| --- | --- | --- |
| 로컬 개발 | Mailpit | 메일을 실제로 발송하지 않고 받아 두는 개발용 SMTP 서버다. 웹 화면에서 받은 메일과 인증 코드를 확인한다. |
| 자동 테스트·CI | GreenMail | 테스트 중 메모리에서 실행하는 SMTP 서버다. 발송 여부와 받는 사람을 검증한다(7절). |
| 부하 테스트 | Mailpit 또는 발송하지 않는 구현 | 실제 발송 서비스의 한도를 소모하지 않는다. |
| 시연·운영 (MVP) | Gmail SMTP | 팀 전용 Gmail 계정과 앱 비밀번호로 발송한다. |
| 시연·운영 (전환 후) | Amazon SES | 실사용자를 받거나 발송량이 늘어나면 전환한다. |

- Gmail을 MVP에 사용하는 이유: 비용이 없고 도메인·DNS 설정 없이 바로 사용할 수 있다.
- Gmail의 알려진 한계: 하루 약 500통으로 발송이 제한되고, 비정상 활동으로 판단되면 계정이 잠겨 회원가입이 멈출 수 있다. 발신 주소가 `@gmail.com`으로 표시되며, 앱 비밀번호 방식은 앞으로 막힐 수 있다.
- SES 전환 방법: SES의 SMTP 창구로 전환하면 환경변수만 바꾼다. SES API로 전환하면 API 구현체를 추가하고 설정을 바꾸며, EC2 인스턴스 역할(IAM Role)을 사용해 보관할 SMTP 계정 정보를 없앤다.
- SES 전환 전에 팀 도메인을 확보해 DKIM·SPF·DMARC를 설정하고, 샌드박스 해제(프로덕션 전환)를 신청한다.
- 발송은 트랜잭션 커밋 후 비동기로 처리하고, SMTP 연결·읽기·쓰기 타임아웃을 지정한다. SMTP 장애가 Actuator Health 결과에 영향을 주지 않도록 메일 헬스 체크는 사용하지 않는다.

### 4.4 결제 PG

Mock 결제(`PAYMENT.md` 1.1절)는 토스페이먼츠 **테스트 환경**으로 진행한다. 테스트 API 키로 승인하므로 실제 결제는 일어나지 않는다. 애플리케이션은 결제 인터페이스(`PaymentGateway`)에만 의존하고, 토스페이먼츠 호출은 그 구현체에 둔다.

| 환경 | 결제 대상 | 용도 |
| --- | --- | --- |
| 로컬 개발·시연 | 토스페이먼츠 테스트 환경 (결제창, API 개별 연동 테스트 키) | 결제창 인증과 결제 승인·조회 API를 실제 연동과 같은 흐름으로 확인한다. |
| 자동 테스트·CI | 테스트용 `PaymentGateway` 구현 | 승인 성공·실패·결과 불명을 지정해 재현한다. 결제창 인증으로만 받을 수 있는 `paymentKey` 없이 결과 반영 로직과 동시성을 검증한다. |

- 토스페이먼츠를 사용하는 이유: 사업자 등록 없이 개발자센터 가입만으로 테스트 키를 받아 실제 PG와 같은 승인 흐름(결제 인증 → 서버 승인)과 오류 응답을 경험할 수 있다. 결제 테스트 내역과 API 로그를 개발자센터에서 확인할 수 있다.
- 결제위젯 대신 결제창(API 개별 연동)을 사용하는 이유: 결제위젯의 상점 전용 키는 이용 계약이 필요해 테스트 단계에서는 문서용 공용 키만 쓸 수 있고, 공용 키로는 개발자센터에 결제 내역과 로그가 남지 않는다.
- 클라이언트 키(`test_ck_`)와 시크릿 키(`test_sk_`)는 같은 세트로 사용한다. 시크릿 키는 서버에서만 쓰고 환경변수(`TOSS_SECRET_KEY`)로 주입하며, 저장소·이미지·로그에 남기지 않는다. 배포 환경에서는 Parameter Store로 관리한다.
- 결제 승인 API는 트랜잭션 밖에서 호출하고 연결·응답 타임아웃을 지정한다. 타임아웃·응답 유실은 실패로 단정하지 않고 결제 조회 API로 상태를 확인한다.
- 오류 시나리오는 테스트 환경 전용 `TossPayments-Test-Code` 헤더로 재현할 수 있다.

## 5. CI/CD

| 구분 | 기술 | 용도 | 선정 이유 |
| --- | --- | --- | --- |
| CI/CD | GitHub Actions | 빌드·테스트·배포 자동화 | Pull Request와 `main`·`deploy` 브랜치 흐름에 빌드 및 테스트를 연결한다. |
| Deployment | EC2 + Docker Compose | 컨테이너 실행 | GHCR 이미지를 내려받아 일관된 방식으로 실행한다. |
| Strategy | Blue/Green | 배포 중 트래픽 전환 | 신규 컨테이너 검증 후 Nginx upstream을 전환하여 중단 시간을 줄인다. |
| Health Check | Actuator Health | 배포 성공 검증 | 신규 인스턴스가 정상 상태일 때만 트래픽을 전환한다. |
| Registry | GHCR | 이미지 버전 관리 | 커밋 SHA 또는 릴리스 태그로 배포 버전을 추적한다. |

### 5.1 구현 상태

| 단계 | 상태 | 내용 |
| --- | --- | --- |
| Docker 이미지 빌드 | 완료 | `Backend/Dockerfile`에서 `bootJar`를 실행하고 non-root 런타임 이미지를 생성한다. |
| GHCR 로그인·게시 | 변경 필요 | 현재 `main` 푸시에서 이미지를 게시한다. 운영 기준에 맞게 `deploy` 푸시로 전환해야 한다. |
| 빌드 캐시 | 완료 | GitHub Actions cache backend를 BuildKit 캐시로 사용한다. |
| `main`·`deploy` CI | 완료 | 두 브랜치 대상 PR과 통합 후 푸시에서 PostgreSQL 서비스를 사용한 Gradle 테스트와 Docker 이미지 빌드를 검증한다. |
| EC2 자동 배포 | 예정 | GHCR 이미지를 내려받아 Docker Compose로 실행한다. |
| Health Check·Blue/Green | 예정 | Actuator Health 확인 후 Nginx upstream을 전환한다. |

현재 워크플로는 이미지 게시 파이프라인이며 운영 서버 자동 배포까지 수행하지 않는다. 단일 EC2 Blue/Green은 배포 중단 시간을 줄이는 전략일 뿐 EC2 자체 장애를 방지하는 고가용성 구성은 아니다.

### 5.2 브랜치 통합 흐름

```text
이슈 브랜치 ── Pull Request ──> main ── Pull Request ──> deploy
                 Backend CI       개발 통합       Backend CI · GHCR 게시
```

- `main`은 개발 통합 브랜치다. 이슈 브랜치는 `main`으로 Pull Request를 생성한다.
- `deploy`는 배포 브랜치다. 배포할 변경은 `main`에서 통합 검증한 뒤 `deploy`로 Pull Request를 생성한다.
- `Backend CI / Test and build image`는 `main`과 `deploy` 대상 Pull Request 및 두 브랜치의 푸시에서 실행한다.
- 위 CI가 성공한 변경만 각 대상 브랜치에 병합하며, `deploy`에 병합하면 GHCR 이미지를 게시한다.
- GitHub Branch Protection에서 위 CI job을 `main`과 `deploy`의 필수 체크로 지정한다.

## 6. Monitoring / Logging

| 구분 | 기술 | 용도 | 선정 이유 |
| --- | --- | --- | --- |
| Application Metrics | Actuator + Micrometer | JVM·HTTP·DB Connection Pool·비즈니스 지표 수집 | Spring Boot와 기본 통합되며 Prometheus 형식으로 메트릭을 노출할 수 있다. |
| Metrics | Prometheus | 시계열 지표 저장 | 주문 요청량, 응답 시간, 오류율 및 재고 충돌 지표를 수집한다. |
| Dashboard | Grafana | 모니터링 시각화 | 부하 테스트 전후 결과와 운영 지표를 대시보드로 비교한다. |
| Log Collection | Grafana Alloy | Docker 로그 수집 | 애플리케이션 로그를 수집해 Loki로 전달한다. |
| Log Storage | Loki | 중앙 로그 저장·조회 | Grafana에서 메트릭과 로그를 함께 조회한다. |

MVP 초기에는 Actuator와 Prometheus를 연결해 JVM·HTTP·DB Connection Pool 메트릭을 수집한다. Grafana 대시보드와 Alloy·Loki 로그 파이프라인은 배포 환경과 핵심 API가 안정된 뒤 단계적으로 완성한다.

## 7. Testing

| 구분 | 기술 | 용도 및 범위 |
| --- | --- | --- |
| Unit Test | JUnit Jupiter, Mockito, AssertJ | 도메인 상태 전이, 금액 계산, 검증 로직을 테스트한다. |
| Repository Test | `@DataJpaTest` | JPA 매핑, 제약조건, 잠금 쿼리 및 조회 쿼리를 검증한다. |
| Integration Test | Spring Boot Test + Testcontainers | 실제 PostgreSQL 환경에서 주문·재고·결제 트랜잭션을 검증한다. |
| API Test | MockMvc | 인증·권한, 요청 검증 및 API 응답 계약을 테스트한다. |
| Mail Test | GreenMail | 이메일 인증 코드 발송을 실제 발송 없이 검증한다. |
| Load Test | k6 | 동시 주문, 재고 선점, 결제 완료 API의 VU·RPS와 Threshold를 검증한다. |

핵심 테스트 시나리오는 다음과 같다.

- 재고 1개에 두 사용자가 동시에 주문하면 한 요청만 성공한다.
- 같은 멱등 키로 주문을 재요청해도 주문이 중복 생성되지 않는다.
- 결제 성공과 예약 만료가 동시에 실행돼도 재고가 한 번만 변경된다.
- 결제 실패·주문 취소·만료 시 선점 재고가 한 번만 반환된다.
- PG 응답이 유실된 결제는 `UNKNOWN`으로 기록하고 조회·보정할 수 있다.

## 8. Collaboration

| 구분 | 기술 | 용도 |
| --- | --- | --- |
| Version Control | Git / GitHub | 소스 코드, 브랜치 및 Pull Request 관리 |
| Issue Tracking | Linear | 이슈, 담당자 및 작업 상태 관리 |
| Documentation | Notion + GitHub `docs` | 기획 문서와 코드에 가까운 기술 문서 관리 |
| Communication | Discord | 팀 커뮤니케이션 및 회의 |
| Design | Figma | UI/UX 설계와 디자인 공유 |
| API Contract | Swagger UI | 백엔드 API 명세 확인 및 프론트엔드 협업 |

## 9. MVP 도입 범위

### MVP에 바로 적용

- Java, Spring Boot, Spring MVC, JPA, Security, Validation
- PostgreSQL, Flyway, Refresh Token·이메일 인증 저장용 Redis
- Gmail SMTP 기반 이메일 인증 코드 발송, 로컬 Mailpit·테스트 GreenMail
- 토스페이먼츠 테스트 환경 기반 Mock 결제 (결제창, 테스트 API 키)
- React, TypeScript, Vite, Tailwind CSS, Axios, React Router
- Docker, Docker Compose, GitHub Actions
- GHCR 이미지 게시(`latest`, 커밋 SHA 태그)
- JUnit 기반 테스트와 핵심 API 통합 테스트
- Actuator Health·Prometheus 메트릭 수집과 구조화된 애플리케이션 로그

### 문제를 확인한 뒤 적용

- Redis 조회 캐시와 분산 환경 보조 기능
- SSE 실시간 재고 전달
- Grafana 대시보드와 Alloy·Loki 로그 파이프라인
- EC2 자동 배포와 Blue/Green 전환
- Amazon SES 발송 전환 (실사용자 오픈 또는 발송량 증가 시)
- 고부하 상황의 재고 처리 최적화
- pgvector 기반 개인화 추천

## 10. 기술 선정 기준

각 기술은 다음 기준으로 선정한다.

1. 프로젝트 요구사항을 해결하기 위해 실제로 필요한가?
2. 팀원이 프로젝트 기간 내 학습하고 적용할 수 있는가?
3. 기존 기술 대비 도입으로 얻는 명확한 이점이 있는가?
4. 운영 및 장애 발생 시 팀이 대응할 수 있는가?
5. 프로젝트 종료 후에도 선정 이유를 설명할 수 있는가?

> 새로운 기술을 사용하는 것 자체를 목표로 하지 않는다. 프로젝트에서 발생한 문제를 재현하고 원인을 분석한 뒤, 이를 해결하기 위한 기술을 선택한다.
