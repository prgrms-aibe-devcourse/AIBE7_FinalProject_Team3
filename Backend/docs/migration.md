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

`.env`에 `JWT_SECRET`과 `EMAIL_VERIFICATION_SECRET`, 소셜 로그인 키 4개(`KAKAO_CLIENT_ID`·`KAKAO_CLIENT_SECRET`·`GOOGLE_CLIENT_ID`·`GOOGLE_CLIENT_SECRET`)가 있어야 백엔드가 기동한다. 소셜 로그인 키는 기본값이 없으며 발급 방법은 8.1절과 `.env.example`에 있다. 메일 키는 비워 두면 Mailpit으로 발송된다([MAIL.md](MAIL.md) 3.3).

```powershell
docker compose up -d --build backend   # postgres·redis·mailpit도 함께 시작된다
docker compose ps                      # postgres·redis가 healthy, backend가 running인지 확인
curl.exe -s http://localhost:8080/actuator/health
```

- PowerShell 5.1은 `curl.exe -d '{"email":"..."}'`의 큰따옴표를 지워 400이 된다. 아래처럼 JSON을 파이프로 넘기고 `--data-binary '@-'`로 읽는다.
- 파이프로 넘기는 문자열은 ASCII로 바뀌므로 닉네임은 영문으로 시험한다. 한글 닉네임은 화면(GR-31)이나 테스트 코드로 확인한다.
- 가입 토큰 쿠키는 `Secure`지만 `curl.exe`는 `localhost`에서 쿠키 파일(`-c`·`-b`)로 저장·전송한다.

CSRF 쿠키를 먼저 받고, 쿠키 파일에서 토큰을 읽어 모든 POST의 `X-XSRF-TOKEN` 헤더에 넣는다. 쿠키만 보내거나 헤더 값이 다르면 403 `ACCESS_DENIED`다.

```powershell
curl.exe -s -i -c signup-cookie.txt http://localhost:8080/api/v1/auth/csrf
$csrfToken = (Get-Content signup-cookie.txt | Where-Object { $_ -match "`tXSRF-TOKEN`t" } | Select-Object -Last 1).Split("`t")[-1]
```

응답은 204이며 `XSRF-TOKEN` 쿠키는 `Secure; SameSite=Lax; Path=/`이고 `HttpOnly`가 없다. Swagger UI에서도 먼저 이 GET을 실행하면 `Try it out`이 쿠키 값을 헤더에 넣는다. CSRF 쿠키 파일에는 가입 토큰도 저장되므로 공유하거나 커밋하지 않는다.

### 6.2 인증 코드 요청과 확인

```powershell
$email = 'signup-test@example.com'

# 인증 코드 요청: 204
"{`"email`":`"$email`"}" | curl.exe -s -w "%{http_code}`n" http://localhost:8080/api/v1/auth/email-verification `
  -b signup-cookie.txt -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'

# 비동기 메일이 Mailpit에 도착한 뒤 코드 확인 (웹 화면 http://127.0.0.1:8025 에서 받는 주소를 확인한다)
$code = [regex]::Match((Invoke-RestMethod http://127.0.0.1:8025/api/v1/message/latest).Text, '\b\d{6}\b').Value

# 코드 확인: 204, Set-Cookie의 email_signup_token(15분)을 signup-cookie.txt에 저장
"{`"email`":`"$email`",`"code`":`"$code`"}" | curl.exe -s -i -b signup-cookie.txt -c signup-cookie.txt `
  http://localhost:8080/api/v1/auth/email-verification/confirm -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'
```

### 6.3 회원가입

```powershell
$signup = 'http://localhost:8080/api/v1/auth/signup'

# 400 INVALID_PASSWORD: 요청 값 오류는 가입 컨텍스트를 소비하지 않아 같은 쿠키로 다시 요청할 수 있다
'{"password":"abc","nickname":"signuptest"}' | curl.exe -s -w "`n%{http_code}`n" -b signup-cookie.txt $signup `
  -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'

# 201: 응답 data에 userId·email·nickname·roles·createdAt, Set-Cookie로 가입 토큰 만료(Max-Age=0)
'{"password":"Password123!","nickname":"signuptest"}' | curl.exe -s -i -b signup-cookie.txt -c signup-cookie.txt $signup `
  -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'

# 401 EMAIL_SIGNUP_CONTEXT_INVALID: 쿠키가 지워져(가입 컨텍스트도 소비됨) 다시 가입할 수 없다
'{"password":"Password123!","nickname":"signuptest2"}' | curl.exe -s -w "`n%{http_code}`n" -b signup-cookie.txt $signup `
  -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'
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

가입한 회원은 7절 로그인 확인에 그대로 쓴다. 시험 데이터 정리는 7.6에서 한다.

### 6.5 프론트엔드 CORS 설정

`.env`의 `CORS_ALLOWED_ORIGINS`에 쿠키 포함 API 요청을 허용할 프론트엔드 Origin을 적는다. 주소는 프로토콜·호스트·포트를 포함하고 끝에 `/`를 붙이지 않는다. 여러 주소는 쉼표로 구분하며 `*`는 사용할 수 없다.

```dotenv
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

Compose는 값이 없거나 비어 있으면 `http://localhost:5173`을 사용한다. 변경 후 `docker compose up -d backend`로 컨테이너를 재생성한다. 호스트의 `bootRun`·IDE는 `.env`를 자동으로 읽지 않으므로 실행 환경에 변수를 직접 지정한다. 호스트 실행에서도 변수가 없으면 `application.yml`의 기본값을 사용하지만, 빈 문자열을 지정하면 기본값 대신 빈 허용 목록이 된다.

프론트엔드는 요청에 `credentials: 'include'`를 지정하고 상태 변경 요청에 `X-XSRF-TOKEN` 헤더를 넣는다. CORS 허용만으로 쿠키가 자동 전송되거나 CSRF 검사가 생략되지는 않는다.

운영에서 프론트와 `/api`를 같은 출처로 서비스하면 CORS 허용 목록을 통한 교차 출처 접근이 필요 없다. 서브도메인 또는 다른 사이트로 분리하면 CSRF 쿠키 접근 범위와 `SameSite` 정책도 함께 검토해야 하므로 Origin 추가만으로 연동이 완료되지는 않는다.

## 7. 로그인 확인

6절에서 가입한 회원(`signup-test@example.com` / `Password123!`)으로 LOCAL 로그인(MEMBER_AUTH 1.3)과 인증 쿠키, Refresh Token 저장을 확인한다. 6절과 같은 PowerShell 세션에서 `signup-cookie.txt`와 `$csrfToken`을 이어서 쓴다. 새 세션이면 6.1의 CSRF 쿠키 발급부터 다시 실행한다.

### 7.1 로그인

```powershell
$login = 'http://localhost:8080/api/v1/auth/login'

# 401 INVALID_CREDENTIALS: 틀린 비밀번호와 없는 이메일은 같은 응답이고 인증 쿠키가 발급되지 않는다
'{"email":"signup-test@example.com","password":"Wrong123!"}' | curl.exe -s -w "`n%{http_code}`n" -b signup-cookie.txt $login `
  -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'
'{"email":"nobody@example.com","password":"Password123!"}' | curl.exe -s -w "`n%{http_code}`n" -b signup-cookie.txt $login `
  -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'

# 200: 이메일은 대소문자·앞뒤 공백을 정규화한다. 응답 data.user에 userId·email·nickname·roles, Set-Cookie로 두 인증 쿠키
'{"email":" Signup-Test@Example.com ","password":"Password123!"}' | curl.exe -s -i -b signup-cookie.txt -c signup-cookie.txt $login `
  -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'
```

200 응답의 `Set-Cookie`를 확인한다. 토큰은 응답 본문에 없다.

| 쿠키 | 속성 |
| --- | --- |
| `access_token` | `Path=/api; Max-Age=899`(또는 900, JWT `exp`까지 남은 초); `Secure; HttpOnly; SameSite=Lax` |
| `refresh_token` | `Path=/api/v1/auth; Max-Age=1209600`(14일); `Secure; HttpOnly; SameSite=Lax` |

로그인은 `XSRF-TOKEN`을 새로 발급하지 않으므로 로그인 전에 받은 `$csrfToken`을 계속 쓴다. 쿠키 파일에는 인증 토큰이 저장되므로 공유하거나 커밋하지 않는다.

### 7.2 보호 API 호출

```powershell
# 200: 쿠키 파일의 access_token(Path=/api)으로 인증된다
curl.exe -s -w "`n%{http_code}`n" -b signup-cookie.txt http://localhost:8080/api/v1/users/me/wishes

# 401 AUTHENTICATION_REQUIRED: 쿠키 없이 호출
curl.exe -s -w "`n%{http_code}`n" http://localhost:8080/api/v1/users/me/wishes

# 200: 내 정보(MEMBER_AUTH 1.9). data에 userId·email·nickname·provider(LOCAL)·roles·profileImageUrl(null)·createdAt만 있다
curl.exe -s -w "`n%{http_code}`n" -b signup-cookie.txt http://localhost:8080/api/v1/users/me

# 401 AUTHENTICATION_REQUIRED: 쿠키 없이 호출
curl.exe -s -w "`n%{http_code}`n" http://localhost:8080/api/v1/users/me
```

`roles`는 로그인 응답의 `roles`와 같다(요청 Access Token의 권한). 판매자 승인은 7.4의 재발급 뒤에 조회해야 반영된다.

### 7.3 Refresh Token 저장 확인

```powershell
# 키는 원문이 아니라 SHA-256 해시다. 로그인할 때마다 토큰 키와 세션 키(auth:refresh-session:{sessionId} → 토큰 해시)가 하나씩 생긴다
docker compose exec -T redis redis-cli --scan --pattern 'auth:refresh-token:*'
docker compose exec -T redis redis-cli --scan --pattern 'auth:refresh-session:*'

# 위에서 나온 키 하나의 TTL(초, 14일 = 1209600 이하)과 값(userId·sessionId·sessionExpiresAt = 로그인 시각 + 30일)
$key = (docker compose exec -T redis redis-cli --scan --pattern 'auth:refresh-token:*' | Select-Object -First 1)
docker compose exec -T redis redis-cli TTL $key
docker compose exec -T redis redis-cli GET $key
```

비활성 계정은 비밀번호가 맞으면 403 `ACCOUNT_DISABLED`, 틀리면 401 `INVALID_CREDENTIALS`다. 확인하려면 상태를 바꾼 뒤 7.1의 200 요청을 다시 보낸다.

```powershell
docker compose exec -T postgres psql -U grab -d grab -c "UPDATE users SET status = 'SUSPENDED' WHERE email = 'signup-test@example.com';"

# 401 AUTHENTICATION_REQUIRED: 정지 직후 기존 access_token으로 내 정보 조회. 계정 상태는 드러내지 않는다
curl.exe -s -w "`n%{http_code}`n" -b signup-cookie.txt http://localhost:8080/api/v1/users/me
```

### 7.4 토큰 재발급

토큰 재발급(MEMBER_AUTH 1.7)과 Refresh Token Rotation을 확인한다. 7.3에서 계정을 정지했다면 먼저 되돌린다.

```powershell
docker compose exec -T postgres psql -U grab -d grab -c "UPDATE users SET status = 'ACTIVE' WHERE email = 'signup-test@example.com';"
```

```powershell
$refresh = 'http://localhost:8080/api/v1/auth/refresh'

# 재사용 확인에 쓸 현재 refresh_token 값(쿠키 파일의 마지막 열)
$oldRefresh = (Select-String -Path signup-cookie.txt -Pattern "refresh_token\t(\S+)").Matches[0].Groups[1].Value

# 204: 본문 없이 Set-Cookie로 새 access_token·refresh_token을 받는다. 쿠키 파일도 새 값으로 바뀐다
curl.exe -s -i -X POST -b signup-cookie.txt -c signup-cookie.txt $refresh -H "X-XSRF-TOKEN: $csrfToken"

# 401 INVALID_TOKEN: 교체된 옛 refresh_token 재사용. 교체 후 10초 안이면 세션은 유지된다
curl.exe -s -w "`n%{http_code}`n" -X POST $refresh -H "Cookie: XSRF-TOKEN=$csrfToken; refresh_token=$oldRefresh" `
  -H "X-XSRF-TOKEN: $csrfToken"

# 204: 세션이 유지됐으므로 새 refresh_token(쿠키 파일)으로 다시 재발급된다
curl.exe -s -w "`n%{http_code}`n" -X POST -b signup-cookie.txt -c signup-cookie.txt $refresh -H "X-XSRF-TOKEN: $csrfToken"

# 401 INVALID_TOKEN: refresh_token 쿠키 없음 / 403 ACCESS_DENIED: CSRF 헤더 없음
curl.exe -s -w "`n%{http_code}`n" -X POST $refresh -H "Cookie: XSRF-TOKEN=$csrfToken" -H "X-XSRF-TOKEN: $csrfToken"
curl.exe -s -w "`n%{http_code}`n" -X POST -b signup-cookie.txt $refresh
```

재발급 응답의 두 쿠키는 로그인과 이름·Path·속성이 같아 브라우저의 기존 쿠키를 덮어쓴다. `refresh_token`의 Max-Age는 로그인 후 30일(절대 만료)을 넘지 않는 범위에서 14일로 다시 시작한다.

Redis에서 교체 결과를 확인한다.

```powershell
# 세션 키는 로그인 때와 같은 sessionId로 남고, 값이 최신 토큰의 해시로 바뀐다
$sessionKey = (docker compose exec -T redis redis-cli --scan --pattern 'auth:refresh-session:*' | Select-Object -First 1)
docker compose exec -T redis redis-cli GET $sessionKey

# 교체된 토큰마다 사용됨 표식이 남는다(sessionId·rotatedAt, TTL은 새 토큰과 같음)
$usedKey = (docker compose exec -T redis redis-cli --scan --pattern 'auth:refresh-token-used:*' | Select-Object -First 1)
docker compose exec -T redis redis-cli HGETALL $usedKey
```

10초가 지난 뒤 `$oldRefresh`로 다시 요청하면 탈취로 보고 세션을 폐기한다. 이후에는 쿠키 파일의 최신 `refresh_token`도 401이므로 7.1의 로그인을 다시 해야 한다. 서버 로그에 `reason=reuse_detected`가 WARN으로 남는다.

### 7.5 로그아웃

로그아웃(MEMBER_AUTH 1.8)을 확인한다. 7.4에서 세션이 폐기됐다면 7.1의 200 로그인을 다시 한다. 다른 세션이 유지되는지 보려고 같은 회원으로 한 번 더 로그인해 별도 쿠키 파일에 둔다.

```powershell
$logout = 'http://localhost:8080/api/v1/auth/logout'

# 같은 회원의 두 번째 로그인 세션(other-cookie.txt). Redis 세션 키가 하나 늘어난다
Copy-Item signup-cookie.txt other-cookie.txt
'{"email":"signup-test@example.com","password":"Password123!"}' | curl.exe -s -o NUL -w "%{http_code}`n" `
  -b other-cookie.txt -c other-cookie.txt $login -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'
(docker compose exec -T redis redis-cli --scan --pattern 'auth:refresh-session:*' | Measure-Object).Count

# 로그아웃할 refresh_token 값(쿠키 파일의 마지막 열)
$loggedOut = (Select-String -Path signup-cookie.txt -Pattern "refresh_token\t(\S+)").Matches[0].Groups[1].Value

# 403 ACCESS_DENIED: CSRF 헤더 없음. 세션과 쿠키 파일의 인증 쿠키가 그대로다
curl.exe -s -w "`n%{http_code}`n" -X POST -b signup-cookie.txt $logout

# 204: 본문 없이 Set-Cookie로 두 인증 쿠키를 만료시킨다(Max-Age=0). curl이 쿠키 파일에서 두 쿠키를 지운다
curl.exe -s -i -X POST -b signup-cookie.txt -c signup-cookie.txt $logout -H "X-XSRF-TOKEN: $csrfToken"
Select-String -Path signup-cookie.txt -Pattern "access_token|refresh_token"   # 출력 없음

# 401 INVALID_TOKEN: 로그아웃한 refresh_token으로 재발급
curl.exe -s -w "`n%{http_code}`n" -X POST $refresh -H "Cookie: XSRF-TOKEN=$csrfToken; refresh_token=$loggedOut" `
  -H "X-XSRF-TOKEN: $csrfToken"

# 204: 반복 로그아웃. 쿠키가 지워진 뒤 요청과 같은 토큰으로 다시 요청해도 같은 결과다
curl.exe -s -w "`n%{http_code}`n" -X POST -b signup-cookie.txt $logout -H "X-XSRF-TOKEN: $csrfToken"
curl.exe -s -w "`n%{http_code}`n" -X POST $logout -H "Cookie: XSRF-TOKEN=$csrfToken; refresh_token=$loggedOut" `
  -H "X-XSRF-TOKEN: $csrfToken"

# 204: 다른 세션은 유지되어 재발급된다. Redis 세션 키는 로그아웃한 하나만 줄었다
curl.exe -s -w "`n%{http_code}`n" -X POST -b other-cookie.txt -c other-cookie.txt $refresh -H "X-XSRF-TOKEN: $csrfToken"
(docker compose exec -T redis redis-cli --scan --pattern 'auth:refresh-session:*' | Measure-Object).Count
```

204 응답의 `Set-Cookie`를 확인한다. 발급과 이름·Path가 같아야 브라우저가 같은 쿠키로 보고 지운다. `XSRF-TOKEN`은 교체·삭제하지 않는다.

| 쿠키 | 속성 |
| --- | --- |
| `access_token` | 빈 값, `Path=/api; Max-Age=0`; `Secure; HttpOnly; SameSite=Lax` |
| `refresh_token` | 빈 값, `Path=/api/v1/auth; Max-Age=0`; `Secure; HttpOnly; SameSite=Lax` |

로그아웃은 Access Token(JWT) 자체를 무효화하지 않는다. 로그아웃 전에 따로 보관한 `access_token` 값은 만료 시각(최대 15분)까지 보호 API에 인증된다(MEMBER_AUTH 1.8).

### 7.6 정리

시험 데이터, Refresh Token 관련 키, 쿠키 파일을 정리한다.

```powershell
docker compose exec -T postgres psql -U grab -d grab -c "DELETE FROM users WHERE email LIKE 'signup-test%';"
'auth:refresh-token:*', 'auth:refresh-session:*', 'auth:refresh-token-used:*' | ForEach-Object {
  docker compose exec -T redis redis-cli --scan --pattern $_ | ForEach-Object { docker compose exec -T redis redis-cli DEL $_ }
}
Remove-Item signup-cookie.txt, other-cookie.txt
```

## 8. 소셜 로그인 확인

KAKAO·GOOGLE 소셜 로그인(MEMBER_AUTH 1.4~1.6)을 실제 제공자로 확인한다. 제공자 인증 화면을 거치므로 시작·콜백은 브라우저로, 가입 완료와 DB·Redis 확인은 6절과 같은 PowerShell로 한다. 자동 테스트(`SocialLoginApiIntegrationTest`)는 제공자 응답(토큰 교환·KAKAO userinfo)을 대역하므로, 콘솔 설정과 실제 제공자 응답은 이 절차로만 확인된다.

### 8.1 제공자 앱과 환경 변수

| 제공자 | 콘솔 설정 |
| --- | --- |
| KAKAO | Kakao Developers > 내 애플리케이션. 앱 키의 REST API 키가 `KAKAO_CLIENT_ID`, 보안 > Client Secret이 `KAKAO_CLIENT_SECRET`(사용 설정). 카카오 로그인 활성화, OpenID Connect 활성화, 동의항목 > 카카오계정(이메일), Redirect URI `http://localhost:8080/api/v1/auth/oauth2/kakao/callback` |
| GOOGLE | Google Cloud Console > API 및 서비스 > 사용자 인증 정보 > OAuth 클라이언트 ID(웹 애플리케이션). 승인된 리디렉션 URI `http://localhost:8080/api/v1/auth/oauth2/google/callback`, 동의 화면 범위 `openid`·`email`. 테스트 모드면 테스트 사용자에 확인할 계정을 추가한다 |

- 네 값을 `.env`에 넣고 `docker compose up -d --build backend`로 다시 띄운다. 값은 `.env`에만 두고 커밋·공유 채널에 붙여 넣지 않는다.
- Redirect URI는 요청한 주소 기준으로 만들어진다. 브라우저에서 `127.0.0.1`이 아니라 콘솔에 등록한 `http://localhost:8080`으로 접속해야 제공자가 받아들인다.
- 결과는 `OAUTH2_FRONTEND_CALLBACK_URL`(기본 `http://localhost:5173/#/auth/callback`)로 리다이렉트된다. 프론트엔드 콜백 화면(GR-47)이 없거나 프론트엔드를 띄우지 않았어도 주소창의 `result`·`error` 쿼리로 결과를 읽는다.

### 8.2 시작 요청

```powershell
# 302: Location이 제공자 인증 화면이고 state·nonce·code_challenge(code_challenge_method=S256)·redirect_uri가 붙는다.
# 인가 요청은 서버 세션에 저장되므로 JSESSIONID 쿠키가 생긴다. 인증 쿠키는 없다
curl.exe -s -i http://localhost:8080/api/v1/auth/oauth2/kakao
curl.exe -s -i http://localhost:8080/api/v1/auth/oauth2/google

# 400 UNSUPPORTED_OAUTH2_PROVIDER: 지원하지 않는 제공자
curl.exe -s -w "`n%{http_code}`n" http://localhost:8080/api/v1/auth/oauth2/naver
```

### 8.3 최초 인증(브라우저)

1. 브라우저 개발자 도구를 열고 `http://localhost:8080/api/v1/auth/oauth2/kakao`로 이동해 제공자 화면에서 로그인·동의한다.
2. 주소창이 `.../#/auth/callback?result=signup_required`이면 성공이다. 개발자 도구 > Application > Cookies > `http://localhost:8080`에서 `oauth2_signup_token`(`Path=/api/v1/auth/oauth2/signup`, 15분, `HttpOnly`·`Secure`)을 확인하고 값을 복사한다. 시작 때 받은 `JSESSIONID` 쿠키는 브라우저에 남지만, 서버 세션(인가 요청)은 콜백 처리 후 무효화돼 더는 쓰이지 않는다. 로그인 상태는 인증 쿠키로만 판단한다.
3. 회원이 아직 없고 가입 컨텍스트만 있는지 확인한다.

```powershell
docker compose exec -T postgres psql -U grab -d grab -c "SELECT count(*) FROM users WHERE provider <> 'LOCAL';"
docker compose exec -T redis redis-cli --scan --pattern 'auth:oauth2-signup-context:*'
```

`error=...`로 끝나면 백엔드 로그에서 사유 종류를 본다(`docker compose logs backend | Select-String "소셜"`). KAKAO는 ID 토큰에 `email_verified`가 없어 OIDC userinfo에서 이메일을 받고, 두 제공자 모두 PKCE와 함께 토큰 교환이 된다(GR-46 M03-04, 2026-10-17 실제 키 확인).

| 증상 | 원인 후보 | 조치 |
| --- | --- | --- |
| 제공자 화면에서 `redirect_uri_mismatch` 등 Redirect URI 오류 | 콘솔에 등록한 주소와 접속 주소가 다르다(`127.0.0.1`과 `localhost`, 포트, 끝의 `/`) | 8.1의 Redirect URI를 그대로 등록하고 `http://localhost:8080`으로 접속한다 |
| `error=OAUTH2_EMAIL_REQUIRED` | KAKAO: 동의항목 카카오계정(이메일)이 꺼져 있거나 사용자가 동의하지 않았다. 이메일이 인증되지 않은 계정이다 | 동의항목을 켜고, 8.6처럼 앱 연결을 끊은 뒤 다시 동의한다 |
| KAKAO만 `error=OAUTH2_AUTHENTICATION_FAILED`, 로그 `reason=invalid_user_info_response` | userinfo 조회가 실패했거나 응답의 `sub`가 ID 토큰과 다르다. OpenID Connect가 꺼져 있으면 ID 토큰부터 발급되지 않는다 | 카카오 로그인 > OpenID Connect 활성화를 확인한다 |
| `error=OAUTH2_AUTHENTICATION_FAILED`, 로그 `reason=invalid_client`·`invalid_grant` 등 토큰 교환 오류 | Client ID·Secret 오타, `.env` 변경 후 컨테이너를 다시 만들지 않았다, KAKAO Client Secret이 사용 안 함이다 | 값을 확인하고 `docker compose up -d backend`로 컨테이너를 다시 만든다(`restart`는 `.env`를 다시 읽지 않는다) |
| GOOGLE 화면에서 `403 access_denied`·앱 테스트 중 안내 | 동의 화면이 테스트 상태이고 계정이 테스트 사용자에 없다 | Google Auth Platform > 대상 > 테스트 사용자에 계정을 추가한다 |

GOOGLE도 `http://localhost:8080/api/v1/auth/oauth2/google`로 같은 순서를 확인한다.

### 8.4 가입 완료

6.1의 CSRF 쿠키 발급을 먼저 실행해 `$csrfToken`을 받는다. 가입 토큰은 8.3에서 복사한 값이다. 닉네임은 영문으로 시험한다(6.1).

```powershell
$signupToken = '<8.3에서 복사한 oauth2_signup_token 값>'
$oauth2Signup = 'http://localhost:8080/api/v1/auth/oauth2/signup'
$cookies = "XSRF-TOKEN=$csrfToken; oauth2_signup_token=$signupToken"

# 400 INVALID_NICKNAME: 닉네임 오류는 가입 컨텍스트를 소비하지 않는다
'{"nickname":"!"}' | curl.exe -s -w "`n%{http_code}`n" $oauth2Signup `
  -H "Cookie: $cookies" -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'

# 201: data.user에 userId·email·nickname·roles. Set-Cookie로 가입 토큰 만료(Max-Age=0)와 access_token·refresh_token
'{"nickname":"socialtest"}' | curl.exe -s -i -c social-cookie.txt $oauth2Signup `
  -H "Cookie: $cookies" -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'

# 401 OAUTH2_SIGNUP_CONTEXT_INVALID: 같은 가입 토큰 재사용
'{"nickname":"socialtest2"}' | curl.exe -s -w "`n%{http_code}`n" $oauth2Signup `
  -H "Cookie: $cookies" -H "X-XSRF-TOKEN: $csrfToken" -H "Content-Type: application/json" --data-binary '@-'

# 200: 발급된 access_token으로 내 정보 조회. provider가 KAKAO 또는 GOOGLE이다
curl.exe -s -w "`n%{http_code}`n" -b social-cookie.txt http://localhost:8080/api/v1/users/me

# USER·ACTIVE, password_hash 없음, provider_user_id(제공자 sub) 저장. 가입 컨텍스트 키는 지워졌다
docker compose exec -T postgres psql -U grab -d grab -c "SELECT email, nickname, role, status, provider, provider_user_id IS NOT NULL AS has_sub, password_hash IS NULL AS no_password FROM users WHERE provider <> 'LOCAL';"
docker compose exec -T redis redis-cli --scan --pattern 'auth:oauth2-signup-context:*'
```

### 8.5 재로그인과 충돌

1. 같은 제공자로 브라우저에서 다시 시작하면 `result=success`이고, 개발자 도구에 `access_token`·`refresh_token` 쿠키가 생긴다. 8.4의 회원 수 조회 결과가 늘지 않아야 한다.
2. 같은 이메일을 쓰는 다른 제공자 계정(또는 6절에서 같은 이메일로 가입한 LOCAL 회원)이 있으면 그 제공자로 시작했을 때 `error=ACCOUNT_LINK_REQUIRED`이고 기존 회원 행은 바뀌지 않는다.
3. 제공자 화면에서 취소하면 `error=OAUTH2_AUTHENTICATION_FAILED`이고 쿠키가 생기지 않는다.

백엔드 로그(`docker compose logs backend | Select-String "소셜"`)에는 제공자 이름·결과 코드·내부 회원 ID만 있어야 한다. 이메일·제공자 식별자·인가 코드·토큰·Client Secret이 보이면 안 된다(NFR-010).

### 8.6 정리

```powershell
docker compose exec -T postgres psql -U grab -d grab -c "DELETE FROM users WHERE provider <> 'LOCAL';"
'auth:oauth2-signup-context:*', 'auth:refresh-token:*', 'auth:refresh-session:*', 'auth:refresh-token-used:*' | ForEach-Object {
  docker compose exec -T redis redis-cli --scan --pattern $_ | ForEach-Object { docker compose exec -T redis redis-cli DEL $_ }
}
Remove-Item social-cookie.txt
```

같은 계정으로 최초 가입부터 다시 확인하려면 제공자 쪽 연결도 끊는다. 카카오는 카카오계정 > 연결된 서비스 관리, 구글은 Google 계정 > 보안 > 타사 앱 및 서비스에서 GRAB 앱 연결을 해제한다.

## 9. 종료 및 초기화

컨테이너 종료:

```bash
docker compose down
```

PostgreSQL 데이터를 포함해 완전히 초기화:

```bash
docker compose down -v
```

`-v` 옵션은 로컬 데이터베이스 데이터를 삭제한다. Flyway를 처음부터 다시 적용해야 할 때만 사용한다.
