# GRAB 코딩 컨벤션

> 대상: Backend(Java 17 / Spring Boot 4.1)
> 원칙: 규칙은 코드 리뷰에서 반복되는 논쟁만 없앤다. 도구가 강제할 수 있는 항목은 도구에 맡긴다.
> 브랜치·커밋·Pull Request 규칙은 [PR_CONVENTION.md](../collaboration/PR_CONVENTION.md)를 따른다.

## 1. 공통

| 항목 | 규칙 |
| --- | --- |
| 인코딩 | UTF-8 |
| 개행 | LF, 파일 끝에 개행 1줄 |
| 들여쓰기 | Java 4칸, JSON·YAML 2칸 (탭 사용 안 함) |
| 주석 | "무엇"이 아니라 "왜"를 적는다. 코드로 설명되는 주석은 달지 않는다. |
| 언어 | 식별자는 영어, 주석·문서·커밋 메시지는 한국어 |
| 비밀값 | API 키·DB 비밀번호·PG Secret은 코드와 저장소에 넣지 않고 환경변수로 주입한다. |

## 2. Java / Spring

### 2.1 패키지 구조

도메인 기준으로 먼저 나누고, 그 안을 레이어로 나눈다.

```text
org.example.grab
├── domain
│   └── drop
│       ├── controller      DropController
│       ├── service         DropService
│       ├── repository      DropRepository
│       ├── entity          Drop, DropStatus
│       └── dto             DropCreateRequest, DropResponse
└── global
    ├── config              SecurityConfig, JpaConfig
    ├── common              ApiResponse, PageResponse
    ├── error               ErrorCode, BusinessException, GlobalExceptionHandler
    └── security            JwtProvider, AuthenticationFilter
```

- 도메인 패키지는 다른 도메인의 `repository`, `entity`를 직접 참조하지 않는다. 필요하면 상대 도메인의 `service`를 통한다.
- `global`은 모든 도메인이 참조할 수 있고, `global`은 특정 도메인을 참조하지 않는다.

### 2.2 네이밍

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| 클래스·인터페이스 | PascalCase | `OrderService` |
| 메서드·변수 | camelCase, 동사로 시작 | `createOrder`, `findByEmail` |
| 상수 | UPPER_SNAKE_CASE | `MAX_RESERVATION_MINUTES` |
| Enum 상수 | UPPER_SNAKE_CASE, 단수 | `OrderStatus.PAID` |
| 패키지 | 소문자, 단수형 | `order`, `payment` |
| 테스트 클래스 | `대상클래스Test` | `OrderServiceTest` |
| 테스트 메서드 | 한국어 `@DisplayName` + 영문 메서드명 | `주문_재고가_없으면_실패한다` |

### 2.3 레이어 규칙

| 레이어 | 책임 | 금지 |
| --- | --- | --- |
| Controller | 요청·응답 변환, 검증(`@Valid`), 권한 확인 | 비즈니스 로직, Entity 직접 반환 |
| Service | 비즈니스 로직, 트랜잭션 경계 | `HttpServletRequest` 등 웹 의존 |
| Repository | 데이터 접근 | 비즈니스 분기 |

- 트랜잭션은 Service에만 선언한다. 조회 메서드는 `@Transactional(readOnly = true)`를 사용한다.
- 의존성은 `final` 필드 + `@RequiredArgsConstructor` 생성자 주입만 사용한다. `@Autowired` 필드 주입은 사용하지 않는다.
- Controller는 항상 `ApiResponse`로 감싼 응답을 반환한다. 형식은 [API_SPEC.md](API_SPEC.md) 1.3·1.4를 따른다.

### 2.4 Entity

- 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 막고, 생성은 정적 팩토리 메서드 또는 `@Builder`로 한다.
- `@Setter`를 쓰지 않는다. 상태 변경은 의도를 드러내는 메서드로 정의한다. (`order.cancel()`)
- 모든 연관관계는 `FetchType.LAZY`로 지정한다. `@ManyToOne`의 기본값(EAGER)에 의존하지 않는다.
- `@Data`, `@EqualsAndHashCode`를 Entity에 사용하지 않는다.
- 금액은 `int`/`long`(KRW 정수), 시각은 `OffsetDateTime`을 사용한다.

### 2.5 DTO

- 요청·응답 DTO는 `record`로 정의하고 `XxxRequest`, `XxxResponse`로 이름 붙인다.
- 응답 DTO는 Entity를 필드로 갖지 않는다. 변환은 DTO의 정적 메서드(`from`)에서 한다.
- 요청 DTO에 Bean Validation 애노테이션을 붙이고, Controller에서 `@Valid`로 검증한다.

### 2.6 예외 처리

- 비즈니스 예외는 `BusinessException(ErrorCode)` 하나로 통일하고, 오류 코드는 `ErrorCode` enum에 HTTP 상태와 함께 정의한다.
- 예외 응답 변환은 `@RestControllerAdvice` 한 곳에서만 한다. Controller에서 `try-catch`로 응답을 만들지 않는다.
- 예외를 삼키지 않는다. `catch` 후 로그만 남기고 넘어가는 코드는 이유를 주석으로 남긴다.

### 2.7 로깅

- `@Slf4j`를 사용하고 `System.out.println`은 쓰지 않는다.
- 파라미터는 문자열 연결 대신 `{}` 플레이스홀더로 넘긴다.
- 로그에 비밀번호·토큰·결제 키를 남기지 않는다.
- 레벨: `ERROR` 즉시 대응 필요, `WARN` 비정상이지만 처리됨, `INFO` 주요 상태 변경, `DEBUG` 개발 확인용.

### 2.8 테스트

- 테스트는 `given / when / then` 주석으로 구분하고, 검증은 AssertJ(`assertThat`)를 사용한다.
- 단위 테스트는 Spring 컨텍스트를 띄우지 않는다. 필요할 때만 `@DataJpaTest`, `@SpringBootTest`를 쓴다.
- 테스트 범위와 도구는 [TECHSTACK.md](TECHSTACK.md) 7장을 따른다.

### 2.9 Flyway 마이그레이션

- 파일명은 `V{번호}__{설명}.sql` (예: `V1__create_user.sql`), 위치는 `src/main/resources/db/migration`.
- 번호는 팀에서 이어서 증가시키고, 이미 병합된 마이그레이션 파일은 수정하지 않는다. 변경은 새 파일로 추가한다.
- 스키마 정의의 기준은 [ERD.md](ERD.md)다.

