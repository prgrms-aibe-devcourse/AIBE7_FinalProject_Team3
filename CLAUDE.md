# GRAB

한정 판매(DROP) 커머스 서비스. 모노레포 구성이다.

- `Backend/` — Spring Boot 4.1 / Java 17. 현재 전체 코드가 여기에 있다.
- `Frontend/` — 미착수 (asset만 존재). 스캐폴딩 시 `Backend/docs/TECHSTACK.md` 3절의 스택을 따른다.

답변과 커밋 메시지는 한국어로 작성한다.

## 명령어 (모두 `Backend/`에서 실행)

- 전체 테스트: `./gradlew test`
- 단일 테스트: `./gradlew test --tests '*OrderServiceTest.methodName'`
- 앱 실행: `./gradlew bootRun` (DB 기동 필요)
- 인프라만 기동: `docker compose up -d postgres redis`
- 전체 스택: `docker compose up -d` (grafana / loki / prometheus / alloy 포함)

## 사양 문서 (구현 전 확인)

- 요구사항 및 인수 조건(AUTH-001 등 ID 체계): `Backend/docs/REQUIREMENTS.md`
- 스키마: `Backend/docs/ERD.md`
- API 명세: `Backend/docs/API_SPEC.md`
- 기술 선정 근거: `Backend/docs/TECHSTACK.md`

구현 내용이 문서와 어긋나면 코드를 먼저 고치지 말고, 문서를 수정할지 먼저 물어본다.

## 규약

- 패키지는 `org.example.grab.{domain,global}` — 도메인별 수직 분할, 공통 관심사는 `global`에 둔다.
- Lombok 어노테이션을 적극 사용하고 보일러플레이트를 직접 작성하지 않는다.
  - 의존성 주입은 `private final` 필드 + `@RequiredArgsConstructor`로 한다. 생성자를 손으로 만들지 않는다.
  - 접근자는 `@Getter`를 쓴다. 엔티티에는 `@Setter`와 `@Data`를 쓰지 않는다 (상태 변경은 의도를 드러내는 메서드로).
  - 엔티티 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 막는다.
  - 로깅은 `@Slf4j`를 쓰고 `LoggerFactory.getLogger(...)`를 직접 호출하지 않는다.
- 스키마 변경은 반드시 Flyway로 한다: `Backend/src/main/resources/db/migration/V{n}__{설명}.sql`
- 설정값은 `${ENV_NAME:기본값}` 형태로 외부화한다 (`application.yml` 참고). 자격증명 하드코딩 금지.
- 커밋 메시지: `feat:`, `fix:`, `chore:` + 한국어 설명
- 브랜치: `TYPE/Kebab-Case` (예: `INFRA/Docker-Setting`)

## 금지

- 이미 커밋된 Flyway 마이그레이션 파일은 수정하지 않는다. 새 버전 파일을 추가한다.
- `build.gradle`에 주석 처리된 Spring AI 의존성을 임의로 활성화하지 않는다 (도입 보류 상태).
- 재고 차감과 주문 생성은 PostgreSQL 트랜잭션 + 행 잠금(`SELECT ... FOR UPDATE`)으로 한 번에 처리한다.
  Redis는 캐시 용도로만 쓰고, 재고 차감의 판단 근거로 삼지 않는다 (Redis를 통째로 날려도 PostgreSQL만으로 복구 가능해야 한다).
- `Backend/build/` 는 빌드 산출물이므로 읽지 않는다.

## 주의

- CI(`.github/workflows/backend-ci.yml`)는 `develop, main` 대상 푸시·PR에서 `Backend/**`가 변경됐을 때만 동작한다.
  조건을 벗어나면 워크플로가 아예 실행되지 않으므로, 체크가 없는 것을 통과로 읽지 않는다.
- 이미지 배포(`.github/workflows/publish-backend.yml`)는 `main` 푸시에서 동작한다.

