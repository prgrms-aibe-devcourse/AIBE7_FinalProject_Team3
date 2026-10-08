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

## 6. 회원가입 확인

Docker로 백엔드를 띄워 이메일 인증부터 회원가입(MEMBER_AUTH 1.2.1~1.2.3)과 DB 저장까지 확인한다. `Backend/` 디렉터리에서 Windows PowerShell 5.1 기준으로 실행한다.

### 6.1 실행

`.env`에 `JWT_SECRET`과 `EMAIL_VERIFICATION_SECRET`이 있어야 백엔드가 기동한다. 메일 키는 비워 두면 Mailpit으로 발송된다([MAIL.md](MAIL.md) 3.3).

```powershell
docker compose up -d --build backend   # postgres·redis·mailpit도 함께 시작된다
docker compose ps                      # postgres·redis가 healthy, backend가 running인지 확인
curl.exe -s http://localhost:8080/actuator/health
```

- PowerShell 5.1은 `curl.exe -d '{"email":"..."}'`의 큰따옴표를 지워 400이 된다. 아래처럼 JSON을 파이프로 넘기고 `--data-binary '@-'`로 읽는다.
- 파이프로 넘기는 문자열은 ASCII로 바뀌므로 닉네임은 영문으로 시험한다. 한글 닉네임은 화면(GR-31)이나 테스트 코드로 확인한다.
- 가입 토큰 쿠키는 `Secure`지만 `curl.exe`는 `localhost`에서 쿠키 파일(`-c`·`-b`)로 저장·전송한다.

### 6.2 인증 코드 요청과 확인

```powershell
$email = 'signup-test@example.com'

# 인증 코드 요청: 204
"{`"email`":`"$email`"}" | curl.exe -s -w "%{http_code}`n" http://localhost:8080/api/v1/auth/email-verification `
  -H "Content-Type: application/json" --data-binary '@-'

# Mailpit에서 코드 확인 (웹 화면 http://127.0.0.1:8025 에서 봐도 된다)
$code = [regex]::Match((Invoke-RestMethod http://127.0.0.1:8025/api/v1/message/latest).Text, '\b\d{6}\b').Value

# 코드 확인: 204, Set-Cookie의 email_signup_token(15분)을 signup-cookie.txt에 저장
"{`"email`":`"$email`",`"code`":`"$code`"}" | curl.exe -s -i -c signup-cookie.txt `
  http://localhost:8080/api/v1/auth/email-verification/confirm -H "Content-Type: application/json" --data-binary '@-'
```

### 6.3 회원가입

```powershell
$signup = 'http://localhost:8080/api/v1/auth/signup'

# 400 INVALID_PASSWORD: 요청 값 오류는 가입 컨텍스트를 소비하지 않아 같은 쿠키로 다시 요청할 수 있다
'{"password":"abc","nickname":"signuptest"}' | curl.exe -s -w "`n%{http_code}`n" -b signup-cookie.txt $signup `
  -H "Content-Type: application/json" --data-binary '@-'

# 201: 응답 data에 userId·email·nickname·roles·createdAt, Set-Cookie로 가입 토큰 만료(Max-Age=0)
'{"password":"Password123!","nickname":"signuptest"}' | curl.exe -s -i -b signup-cookie.txt -c signup-cookie.txt $signup `
  -H "Content-Type: application/json" --data-binary '@-'

# 401 EMAIL_SIGNUP_CONTEXT_INVALID: 쿠키가 지워져(가입 컨텍스트도 소비됨) 다시 가입할 수 없다
'{"password":"Password123!","nickname":"signuptest2"}' | curl.exe -s -w "`n%{http_code}`n" -b signup-cookie.txt $signup `
  -H "Content-Type: application/json" --data-binary '@-'
```

409는 다른 이메일로 6.2를 다시 진행한 뒤 확인한다.

- `DUPLICATE_NICKNAME`: 이미 가입된 닉네임을 대소문자만 바꿔(`SIGNUPTEST`) 보낸다. 가입 컨텍스트가 남아 다른 닉네임으로 다시 요청하면 201이다.
- `DUPLICATE_EMAIL`: 가입한 이메일로 6.2를 다시 진행하면 코드 확인이 409가 되고 쿠키가 발급되지 않는다(1.2.2). 같은 이메일의 코드 재요청은 60초가 지나야 한다(429).

### 6.4 DB 저장 확인

```powershell
docker compose exec -T postgres psql -U grab -d grab -c "SELECT email, nickname, role, status, provider, left(password_hash, 31) AS hash_prefix FROM users WHERE email = 'signup-test@example.com';"
```

- `role`은 `USER`, `status`는 `ACTIVE`, `provider`는 `LOCAL`이다.
- `hash_prefix`는 `$argon2id$v=19$m=19456,t=2,p=1$`이고, 원문 비밀번호는 어디에도 저장되지 않는다.

시험 데이터와 쿠키 파일 정리:

```powershell
docker compose exec -T postgres psql -U grab -d grab -c "DELETE FROM users WHERE email LIKE 'signup-test%';"
Remove-Item signup-cookie.txt
```

## 7. 종료 및 초기화

컨테이너 종료:

```bash
docker compose down
```

PostgreSQL 데이터를 포함해 완전히 초기화:

```bash
docker compose down -v
```

`-v` 옵션은 로컬 데이터베이스 데이터를 삭제한다. Flyway를 처음부터 다시 적용해야 할 때만 사용한다.
