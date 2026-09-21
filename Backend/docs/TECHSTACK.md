# GRAB 기술 스택

> 상태: MVP 구현 기준 초안
> 원칙: 문제를 먼저 정의하고, 해결에 필요한 기술만 단계적으로 도입한다.

## 1. Backend

| 구분 | 기술 | 버전 | 용도 및 선정 이유 |
| --- | --- | --- | --- |
| Language | Java | 17 | 팀의 학습 경험과 라이브러리 호환성이 안정적이며 Spring Boot 4.1의 최소 요구 버전을 충족한다. |
| Framework | Spring Boot | 4.1.x | 웹, 보안, 데이터 접근, 모니터링 환경을 일관되게 구성한다. |
| Web | Spring MVC | Boot 관리 | REST API와 SSE 구현에 사용하며 블로킹 방식의 JPA 환경에 적합하다. |
| ORM | Spring Data JPA / Hibernate | Boot 관리 | 주문·옵션·결제 도메인의 관계 매핑과 트랜잭션을 관리한다. 복잡한 조회는 DTO Projection 또는 별도 쿼리로 처리한다. |
| Security | Spring Security | Boot 관리 | 사용자 인증과 USER·SELLER·ADMIN 권한 및 리소스 소유권을 검증한다. |
| Validation | Jakarta Bean Validation | Boot 관리 | 요청 DTO와 상태별 필수값을 검증한다. |
| Build | Gradle Wrapper | 8.14+ 또는 9.x | 로컬과 CI에서 동일한 빌드 도구 버전을 사용한다. |
| API Docs | SpringDoc OpenAPI / Swagger UI | 3.1.1 | Spring Boot 4 기반 API 명세를 자동 생성하고 프론트엔드와 공유한다. |
| Monitoring Endpoint | Spring Boot Actuator | Boot 관리 | 상태 확인, 메트릭 노출 및 배포 성공 여부를 검증한다. |

## 2. Database

| 구분 | 기술 | 도입 시점 | 용도 및 선정 이유 |
| --- | --- | --- | --- |
| RDBMS | PostgreSQL | MVP | 회원·DROP·주문·재고·결제 데이터를 저장하는 영속 원장이다. 트랜잭션, 행 잠금, 인덱스 및 향후 pgvector 확장이 가능하다. |
| Cache | Redis | 병목 확인 후 | 조회 캐시, 임시 데이터, 분산 환경의 보조 기능에 사용한다. 주문과 재고의 최종 원장으로 사용하지 않는다. |
| Migration | Flyway | MVP | ERD 변경 이력을 SQL 마이그레이션으로 관리하고 환경별 스키마를 일치시킨다. |

재고 정합성은 PostgreSQL 트랜잭션과 행 잠금으로 먼저 보장한다. Redis는 부하 테스트를 통해 병목이 확인된 후 캐시 또는 보조 저장소로 도입한다.

## 3. Frontend

| 구분 | 기술 | 버전 | 용도 및 선정 이유 |
| --- | --- | --- | --- |
| Language | TypeScript | 5.x | API 요청·응답과 클라이언트 상태의 타입을 명확하게 관리한다. |
| Framework | React | 19.x | 컴포넌트 기반으로 소비자·판매자·관리자 화면을 구성한다. |
| Build Tool | Vite | 8.x | 빠른 개발 서버와 단순한 SPA 빌드 환경을 제공한다. |
| Styling | Tailwind CSS | 4.x | 짧은 MVP 기간에 일관된 반응형 UI를 구현한다. |
| HTTP Client | Axios | 1.x | 공통 오류 처리와 인증 헤더 및 토큰 재발급 인터셉터를 구성한다. |
| Routing | React Router | 7.x | 소비자·판매자·관리자 라우팅과 권한별 화면을 분리한다. |
| Server State | TanStack Query | 5.x | API 데이터 캐싱, 재조회, 로딩·오류 상태를 관리한다. |
| Client State | React Context 또는 Zustand | 필요 시 확정 | 로그인 사용자 정보 등 작은 전역 상태만 관리한다. 서버 응답 데이터는 TanStack Query에서 관리한다. |

## 4. Infrastructure / Deployment

| 구분 | 기술 | 용도 | 선정 이유 |
| --- | --- | --- | --- |
| Cloud | AWS | 인프라 환경 | EC2·RDS·S3 등 운영 자원을 한 환경에서 관리한다. |
| Compute | EC2 | 애플리케이션 서버 | Docker 기반 배포와 서버 운영 구성을 직접 학습하고 제어할 수 있다. |
| Database | Amazon RDS for PostgreSQL | 운영 DB | 자동 백업, 복구 및 관리형 PostgreSQL 환경을 사용한다. |
| Storage | Amazon S3 | DROP 이미지 저장 | 애플리케이션 서버와 이미지 파일을 분리하고 Presigned URL 업로드를 지원한다. |
| Container | Docker | 애플리케이션 컨테이너화 | 개발·CI·운영 환경 차이를 줄인다. |
| Container Management | Docker Compose | 컨테이너 실행 | Kubernetes 없이 애플리케이션과 운영 도구를 단순하게 관리한다. |
| Reverse Proxy | Nginx | TLS 종료 및 트래픽 전환 | 외부 요청을 애플리케이션으로 전달하고 Blue/Green 포트 전환에 사용한다. |
| Container Registry | GHCR | Docker 이미지 저장 | GitHub Actions와 권한 및 배포 흐름을 연계한다. |
| Secrets | AWS Systems Manager Parameter Store | 환경변수·비밀값 관리 | DB 비밀번호와 PG Secret Key가 저장소 및 이미지에 포함되는 것을 방지한다. |
| DNS / TLS | Route 53 + ACM | 도메인·인증서 | 운영 서비스에 HTTPS를 적용한다. |

RDS와 S3를 기본 운영안으로 사용한다. 비용이나 운영 일정 때문에 Neon 또는 Supabase를 검토할 수 있지만, 같은 역할의 서비스를 동시에 사용하지 않고 배포 전 하나로 확정한다.

## 5. CI/CD

| 구분 | 기술 | 용도 | 선정 이유 |
| --- | --- | --- | --- |
| CI/CD | GitHub Actions | 빌드·테스트·배포 자동화 | Pull Request와 main 브랜치 흐름에 빌드 및 테스트를 연결한다. |
| Deployment | EC2 + Docker Compose | 컨테이너 실행 | GHCR 이미지를 내려받아 일관된 방식으로 실행한다. |
| Strategy | Blue/Green | 배포 중 트래픽 전환 | 신규 컨테이너 검증 후 Nginx upstream을 전환하여 중단 시간을 줄인다. |
| Health Check | Actuator Health | 배포 성공 검증 | 신규 인스턴스가 정상 상태일 때만 트래픽을 전환한다. |
| Registry | GHCR | 이미지 버전 관리 | 커밋 SHA 또는 릴리스 태그로 배포 버전을 추적한다. |

단일 EC2에서 수행하는 Blue/Green은 배포 중단 시간을 줄이는 전략이며, EC2 자체 장애까지 방지하는 고가용성 구성은 아니다.

## 6. Monitoring / Logging

| 구분 | 기술 | 용도 | 선정 이유 |
| --- | --- | --- | --- |
| Application Metrics | Actuator + Micrometer | JVM·HTTP·DB Connection Pool·비즈니스 지표 수집 | Spring Boot와 기본 통합되며 Prometheus 형식으로 메트릭을 노출할 수 있다. |
| Metrics | Prometheus | 시계열 지표 저장 | 주문 요청량, 응답 시간, 오류율 및 재고 충돌 지표를 수집한다. |
| Dashboard | Grafana | 모니터링 시각화 | 부하 테스트 전후 결과와 운영 지표를 대시보드로 비교한다. |
| Log Collection | Grafana Alloy | Docker 로그 수집 | 애플리케이션 로그를 수집해 Loki로 전달한다. |
| Log Storage | Loki | 중앙 로그 저장·조회 | Grafana에서 메트릭과 로그를 함께 조회한다. |

MVP 초기에는 Actuator, 구조화 로그 및 Docker 로그 확인부터 적용한다. Prometheus·Grafana·Alloy·Loki는 배포 환경과 핵심 API가 안정된 뒤 단계적으로 연결한다.

## 7. Testing

| 구분 | 기술 | 용도 및 범위 |
| --- | --- | --- |
| Unit Test | JUnit Jupiter, Mockito, AssertJ | 도메인 상태 전이, 금액 계산, 검증 로직을 테스트한다. |
| Repository Test | `@DataJpaTest` | JPA 매핑, 제약조건, 잠금 쿼리 및 조회 쿼리를 검증한다. |
| Integration Test | Spring Boot Test + Testcontainers | 실제 PostgreSQL 환경에서 주문·재고·결제 트랜잭션을 검증한다. |
| API Test | MockMvc | 인증·권한, 요청 검증 및 API 응답 계약을 테스트한다. |
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
- PostgreSQL, Flyway
- React, TypeScript, Vite, Tailwind CSS, Axios, React Router
- Docker, Docker Compose, GitHub Actions
- JUnit 기반 테스트와 핵심 API 통합 테스트
- Actuator Health와 구조화된 애플리케이션 로그

### 문제를 확인한 뒤 적용

- Redis 캐시와 분산 환경 보조 기능
- SSE 실시간 재고 전달
- Prometheus, Grafana, Alloy, Loki 전체 구성
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
