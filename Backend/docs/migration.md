# GRAB Backend

## 1. 사전 준비

- Java 17
- Docker Desktop

프로젝트의 Gradle Wrapper를 사용하므로 Gradle을 별도로 설치할 필요는 없다.

## 2. 로컬 PostgreSQL 실행

`Backend` 디렉터리에서 다음 명령을 실행한다.

```bash
docker compose up -d postgres
docker compose ps
```

기본 연결 정보는 다음과 같다.

| 항목 | 값 |
| --- | --- |
| Host | `localhost` |
| Port | `5432` |
| Database | `grab` |
| Username | `grab` |
| Password | `grab` |

로컬 비밀번호를 변경하려면 `.env.example`을 `.env`로 복사하고 값을 수정한다. `.env`는 Git에 커밋하지 않는다. 비밀번호를 변경한 경우 애플리케이션 실행 환경의 `DB_PASSWORD`에도 같은 값을 설정해야 한다.

## 3. 애플리케이션 실행

Windows PowerShell:

```powershell
.\gradlew.bat bootRun
```

macOS 또는 Linux:

```bash
./gradlew bootRun
```

별도 환경변수가 없으면 애플리케이션은 다음 주소로 접속한다.

```text
jdbc:postgresql://localhost:5432/grab
```

환경별 값은 다음 변수로 덮어쓸 수 있다.

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

## 4. Flyway

마이그레이션 파일 위치:

```text
src/main/resources/db/migration
```

파일명 규칙:

```text
V{번호}__{설명}.sql
```

예시:

```text
V1__init_schema.sql
V2__add_order_index.sql
```

애플리케이션 시작 시 Flyway가 미적용 파일을 버전 순서대로 실행한다. 적용 결과는 PostgreSQL의 `flyway_schema_history` 테이블에 저장된다.

- 이미 병합되어 적용된 마이그레이션 파일은 수정하지 않는다.
- 스키마 변경은 다음 번호의 새 파일로 추가한다.
- JPA는 `ddl-auto: validate`로 Flyway 스키마와 Entity의 일치 여부만 확인한다.

## 5. 선택 서비스

백엔드까지 컨테이너로 실행:

```bash
docker compose --profile app up -d --build
```

Redis 실행:

```bash
docker compose --profile cache up -d redis
```

모니터링 컨테이너 실행:

```bash
docker compose --profile observability up -d
```

Redis와 모니터링 구성은 도입 시점 전까지 기본 로컬 개발에 필요하지 않다.

## 6. 종료 및 초기화

컨테이너 종료:

```bash
docker compose down
```

PostgreSQL 데이터를 포함해 완전히 초기화:

```bash
docker compose down -v
```

`-v` 옵션은 로컬 데이터베이스 데이터를 삭제한다. Flyway를 처음부터 다시 적용해야 할 때만 사용한다.
