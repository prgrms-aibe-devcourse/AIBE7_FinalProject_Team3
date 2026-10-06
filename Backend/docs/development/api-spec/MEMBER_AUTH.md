# 회원 및 인증 API 명세

## 1. 회원 및 인증 API

### 1.1 CSRF 토큰 발급

```http
GET /api/v1/auth/csrf
```

- **인증**: 불필요

**응답:** `204 No Content`

```http
Set-Cookie: XSRF-TOKEN=<csrf-token>; Secure; SameSite=Lax; Path=/
```

`XSRF-TOKEN`은 인증 토큰이 아니며 상태 변경 요청의 `X-XSRF-TOKEN` 헤더에 같은 값을 전달하기 위해 JavaScript에서 읽을 수 있다.

### 1.2 LOCAL 회원가입

`LOCAL` 회원가입은 이메일 소유를 인증 코드로 확인한 뒤 진행한다. KAKAO·GOOGLE 회원가입은 1.4~1.6절의 OAuth2 흐름을 사용한다.

1. 인증 코드 요청(1.2.1): 이메일로 6자리 인증 코드를 발송한다.
2. 인증 코드 확인(1.2.2): 코드가 맞으면 일회용 가입 컨텍스트를 발급하고 원문을 HttpOnly 쿠키로 전달한다.
3. 회원가입 완료(1.2.3): 가입 컨텍스트에 저장된 인증된 이메일과 요청한 비밀번호·닉네임으로 회원을 생성한다.

회원가입 완료 요청은 이메일을 요청 본문으로 받지 않는다. 인증하지 않은 이메일로 바꿔 가입하는 것을 막기 위함이다.

**이메일 검증 규칙** (1.2.1, 1.2.2 공통):
- `email`은 필수다. 키 누락, `null`, 빈 문자열, 앞뒤 공백 제거 후 빈 문자열이면 `VALIDATION_FAILED`로 거부한다.
- 이메일은 앞뒤 공백을 제거하고 전체를 소문자로 변환해 정규화한 뒤 검증·중복 검사·저장한다.
  대소문자·공백만 다른 이메일로 중복 가입하거나 가입 때와 다르게 입력해 로그인에 실패하는 것을 막기 위함이다.
- 정규화 후 중간에 공백이 포함되면 `INVALID_EMAIL`로 거부한다.
- 이메일은 유효한 형식이어야 한다. 형식이 잘못되면 `INVALID_EMAIL`로 거부한다.
- 정규화한 이메일은 254자 이하여야 한다. 초과하면 `INVALID_EMAIL`로 거부한다.
- 한 필드가 여러 규칙을 위반하면 필수값 → 길이 → 형식(중간 공백 포함) 순서에서 먼저 걸리는 사유 하나만 `fieldErrors`에 담는다.

**인증 코드와 가입 컨텍스트 규칙:**

| 항목 | 규칙 |
| --- | --- |
| 인증 코드 | 6자리 숫자, 발송 후 5분간 유효, 확인에 성공하면 즉시 폐기 |
| 코드 재요청 | 같은 이메일로 새 코드를 요청하면 이전 코드는 폐기되고 가장 최근 코드만 유효하다 |
| 확인 시도 | 코드 하나당 5회까지 틀릴 수 있다. 5회째 틀리면 코드를 폐기한다 |
| 재발송 간격 | 같은 이메일은 60초가 지나야 다시 요청할 수 있다 |
| 발송 한도 | 같은 이메일은 24시간에 10회, 같은 IP는 1시간에 30회까지 요청할 수 있다 |
| 가입 컨텍스트 | 코드 확인 성공 시 발급, 15분간 유효, 회원가입에 성공하면 즉시 폐기 |
| 저장 | 인증 코드와 가입 컨텍스트 토큰은 원문이 아닌 해시로 Redis에 TTL과 함께 저장한다 |

- 인증 코드와 가입 컨텍스트 토큰의 원문은 응답 본문과 로그에 남기지 않는다. 인증 코드는 메일로만 전달한다.
- 발송 한도 값은 서버 설정으로 관리한다.

#### 1.2.1 이메일 인증 코드 요청

```http
POST /api/v1/auth/email-verification
```

- **인증**: 불필요

**요청:**

```json
{
  "email": "user@example.com"
}
```

**응답:** `204 No Content`

- 이미 가입된 이메일이어도 같은 응답을 반환하고 인증 코드를 발송한다. 응답만으로 가입 여부를 알 수 없게 하기 위함이며, 가입 여부는 이메일 소유를 확인한 뒤(1.2.2) 알린다.
- 메일은 비동기로 발송하며 응답은 발송 완료를 기다리지 않는다. 발송에 실패해도 응답은 바뀌지 않으며, 사용자는 재발송 간격이 지난 뒤 다시 요청할 수 있다.
- 재발송 간격·발송 한도 제한은 가입 여부와 관계없이 똑같이 적용한다.

**오류 코드:**
- `INVALID_REQUEST`
- `VALIDATION_FAILED`
- `INVALID_EMAIL`
- `EMAIL_VERIFICATION_RESEND_TOO_SOON`

#### 1.2.2 이메일 인증 코드 확인

```http
POST /api/v1/auth/email-verification/confirm
```

- **인증**: 불필요

**요청:**

```json
{
  "email": "user@example.com",
  "code": "123456"
}
```

**응답:** `204 No Content`

```http
Set-Cookie: email_signup_token=<one-time-token>; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth/signup; Max-Age=900
```

**검증 규칙:**
- `email`은 1.2절의 이메일 검증 규칙을 따른다.
- `code`는 필수이며 숫자 6자리여야 한다. 누락되거나 형식이 다르면 `VALIDATION_FAILED`로 거부하고, 이 경우는 확인 시도 횟수에 포함하지 않는다.
- 유효한 코드가 없으면(발송 후 5분 경과, 요청한 적 없음, 이미 사용·폐기됨) `EMAIL_VERIFICATION_CODE_EXPIRED`로 거부한다.
- 코드가 틀리면 `EMAIL_VERIFICATION_CODE_MISMATCH`로 거부한다. 5회째 틀리면 코드를 폐기하고 `EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED`로 거부한다. 이후에는 새 코드를 요청해야 한다.
- 코드가 맞으면 코드를 폐기한다. 이미 가입된 이메일이면 가입 컨텍스트를 발급하지 않고 `DUPLICATE_EMAIL`로 거부한다. 이 시점에는 이메일 소유가 확인됐으므로 가입 여부를 알려도 된다.

**오류 코드:**
- `INVALID_REQUEST`
- `VALIDATION_FAILED`
- `INVALID_EMAIL`
- `EMAIL_VERIFICATION_CODE_MISMATCH`
- `EMAIL_VERIFICATION_CODE_EXPIRED`
- `EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED`
- `DUPLICATE_EMAIL`

#### 1.2.3 회원가입 완료

```http
POST /api/v1/auth/signup
```

- **인증**: `email_signup_token` HttpOnly 쿠키
- **CSRF**: `X-XSRF-TOKEN` 헤더 필요

**요청:**

```json
{
  "password": "Password123!",
  "nickname": "드롭헌터"
}
```

서버는 가입 컨텍스트에 저장된 인증된 이메일을 사용한다. 클라이언트가 이메일을 지정할 수 없다.

**응답:** `201 Created`

```http
Set-Cookie: email_signup_token=; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth/signup; Max-Age=0
```

```json
{
  "success": true,
  "data": {
    "userId": "6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8",
    "email": "user@example.com",
    "nickname": "드롭헌터",
    "roles": ["USER"],
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

**가입 컨텍스트:**
- 요청 본문을 검증하기 전에 가입 컨텍스트를 먼저 확인한다.
- 쿠키가 없거나 알 수 없는·이미 사용한 토큰이면 `EMAIL_SIGNUP_CONTEXT_INVALID`, 유효시간이 지났으면 `EMAIL_SIGNUP_CONTEXT_EXPIRED`로 거부한다.
- 가입 컨텍스트는 회원가입에 성공하면 한 번만 소비한다.
- 요청 값 오류(`VALIDATION_FAILED`, `INVALID_PASSWORD`, `INVALID_NICKNAME`)와 `DUPLICATE_NICKNAME`은 가입 컨텍스트를 소비하지 않는다. 사용자는 컨텍스트가 만료되기 전까지 값을 바꿔 다시 요청할 수 있다.
- 코드 확인 이후 같은 이메일로 다른 회원이 생성됐으면 가입 컨텍스트를 소비하고 `DUPLICATE_EMAIL`로 거부한다.

**검증 규칙:**
- MVP 회원가입에서는 휴대폰 번호를 받지 않는다.
- `password`, `nickname`은 필수다. 필수값이 없으면 `VALIDATION_FAILED`로 거부하고, 없는 항목 전부를 `fieldErrors`로 반환한다.
  - `password`: 키 누락, `null`, 빈 문자열인 경우. 비밀번호는 trim하지 않으므로 공백 문자만으로 된 값은 필수값 위반이 아니라 비밀번호 규칙 위반(`INVALID_PASSWORD`)으로 거부한다.
  - `nickname`: 키 누락, `null`, 빈 문자열, 앞뒤 공백 제거 후 빈 문자열인 경우
- 이메일은 중복될 수 없다.
- 비밀번호는 최소 8자 이상, 최대 64자 이하여야 한다. 벗어나면 `INVALID_PASSWORD`로 거부한다. 허용 문자가 모두 ASCII이므로 길이는 문자(char) 수 기준이다.
- 비밀번호에는 영문 대소문자(`A-Z`, `a-z`), 숫자(`0-9`), ASCII 특수문자 32개(``!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~``)만 사용할 수 있다. 한글·이모지 등 그 밖의 문자가 있으면 `INVALID_PASSWORD`로 거부한다.
  입력 환경에 따라 같은 모양의 문자가 다른 코드로 입력돼 로그인에 실패하는 것을 막기 위함이다.
- 비밀번호는 영문, 숫자, 특수문자를 각각 1자 이상 포함해야 한다. 영문은 대소문자 중 어느 쪽이든 1자 이상이면 된다. 하나라도 없으면 `INVALID_PASSWORD`로 거부한다.
- 비밀번호에 공백 문자(스페이스, 탭, 개행 등)가 포함되면 `INVALID_PASSWORD`로 거부한다. 서버는 비밀번호를 trim하거나 가공하지 않는다.
- 비밀번호는 Argon2id로 해시해 저장한다.
- `nickname`은 회원이 직접 정하는 닉네임이며 본명이 아니다. 본명은 수집하지 않는다.
- 닉네임은 앞뒤 공백을 제거한 뒤 검증·저장한다.
- 닉네임은 2자 이상 10자 이하여야 한다. 벗어나면 `INVALID_NICKNAME`으로 거부한다. 길이는 앞뒤 공백을 제거한 값의 문자(코드 포인트) 수 기준이다.
- 닉네임은 한글(완성형), 영문, 숫자, 밑줄(`_`)만 허용한다. 중간 공백을 포함해 그 밖의 문자가 있으면 `INVALID_NICKNAME`으로 거부한다.
- 닉네임은 중복될 수 없다. 중복 판단은 대소문자를 구분하지 않으며(`Grab`과 `grab`은 같은 닉네임), 저장은 입력한 대소문자 그대로 한다.
  동시 요청으로 사전 검사를 함께 통과하더라도 DB 유니크 인덱스 위반을 `DUPLICATE_NICKNAME`으로 응답한다.
- 탈퇴한 회원의 닉네임은 익명 값으로 바뀌므로 다른 회원이 다시 사용할 수 있다.
- 닉네임 규칙은 1.6절 소셜 회원가입과 1.10절 내 정보 수정에도 동일하게 적용한다.

**여러 항목이 동시에 검증에 실패한 경우:**
- `fieldErrors`에는 필드마다 사유를 하나만 담는다. 한 필드가 여러 규칙을 위반하면 아래 순서에서 먼저 걸리는 사유를 쓴다.
  - `password`: 필수값 → 길이 → 공백 → 허용 문자 → 문자 조합
  - `nickname`: 필수값 → 길이 → 허용 문자
- `fieldErrors`는 `password`, `nickname` 순서로 정렬한다.
- 최상위 `error.code`는 `fieldErrors`에 남은 각 사유의 오류 코드(위 검증 규칙에서 정한 코드)가 모두 같으면 그 코드를, 서로 다르면 `VALIDATION_FAILED`를 사용한다.
  - 예: 비밀번호만 잘못됨 → `INVALID_PASSWORD`, 비밀번호와 닉네임이 모두 잘못됨 → `VALIDATION_FAILED`, 두 필드가 모두 누락됨 → `VALIDATION_FAILED`
- `fieldErrors`는 필드 이름과 사유(`reason`)만 담고 거부된 입력값은 담지 않는다.
- 1.2.1·1.2.2의 여러 항목 동시 실패도 같은 방식으로 처리한다. 1.2.2의 `fieldErrors`는 `email`, `code` 순서로 정렬한다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "요청 값 검증에 실패했습니다.",
    "fieldErrors": [
      { "field": "password", "reason": "비밀번호는 8자 이상 64자 이하여야 합니다." },
      { "field": "nickname", "reason": "닉네임은 2자 이상 10자 이하여야 합니다." }
    ]
  }
}
```

> `reason` 문구는 예시이며, 클라이언트는 `code`와 `field`로 분기한다.

**요청 본문 오류:**
- 요청 본문이 없거나 JSON 형식이 잘못되면 `INVALID_REQUEST`로 거부하고 `fieldErrors`는 빈 배열로 반환한다. 1.2.1·1.2.2에도 같게 적용한다.

**오류 코드:**
- `EMAIL_SIGNUP_CONTEXT_INVALID`
- `EMAIL_SIGNUP_CONTEXT_EXPIRED`
- `INVALID_REQUEST`
- `VALIDATION_FAILED`
- `DUPLICATE_EMAIL`
- `INVALID_PASSWORD`
- `INVALID_NICKNAME`
- `DUPLICATE_NICKNAME`

### 1.3 LOCAL 로그인

```http
POST /api/v1/auth/login
```

- **인증**: 불필요

**요청:**

```json
{
  "email": "user@example.com",
  "password": "Password123!"
}
```

요청 이메일은 1.2절과 동일한 규칙으로 정규화한 뒤 회원을 조회한다.

**응답:**

```http
Set-Cookie: access_token=<jwt>; HttpOnly; Secure; SameSite=Lax; Path=/api; Max-Age=<access-ttl>
Set-Cookie: refresh_token=<token>; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=<refresh-ttl>
```

Access Token과 Refresh Token은 응답 본문에 포함하지 않는다. Access Token은 서버에 저장하지 않고, Refresh Token은 해시만 Redis에 TTL과 함께 저장한다.

```json
{
  "success": true,
  "data": {
    "user": {
      "userId": "6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8",
      "email": "user@example.com",
      "nickname": "드롭헌터",
      "roles": ["USER"]
    }
  }
}
```

**오류 코드:**
- `INVALID_CREDENTIALS`
- `ACCOUNT_DISABLED`

### 1.4 KAKAO·GOOGLE 소셜 로그인 시작

```http
GET /api/v1/auth/oauth2/{provider}
```

- **인증**: 불필요
- **경로 변수**: `provider`는 `kakao` 또는 `google`

**응답:** `302 Found`

```http
Location: <provider-authorization-uri>
```

서버가 OAuth2 Authorization Code 요청과 `state`를 생성해 해당 제공자의 인증 화면으로 이동시킨다. 리다이렉트 대상은 서버 설정으로 관리하며 요청에서 임의의 반환 URL을 받지 않는다.

**오류 코드:**
- `UNSUPPORTED_OAUTH2_PROVIDER`

### 1.5 소셜 로그인 콜백

```http
GET /api/v1/auth/oauth2/{provider}/callback
```

- **인증**: 불필요
- **호출 주체**: KAKAO 또는 GOOGLE OAuth2 제공자

클라이언트가 직접 호출하는 API가 아니다. 서버는 인가 코드와 `state`를 검증하고 제공자의 고유 사용자 식별자와 검증된 이메일을 조회한다. 제공자 이메일은 1.2절과 동일한 규칙으로 정규화한 뒤 기존 회원 여부를 판단하고 가입 컨텍스트에 저장한다.

기존 소셜 회원이면 Access Token과 Refresh Token을 1.3절과 동일한 HttpOnly 쿠키로 발급하고 프론트엔드 콜백 화면으로 이동시킨다.

```http
HTTP/1.1 302 Found
Set-Cookie: access_token=<jwt>; HttpOnly; Secure; SameSite=Lax; Path=/api; Max-Age=<access-ttl>
Set-Cookie: refresh_token=<token>; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=<refresh-ttl>
Location: <frontend-auth-callback>?result=success
```

최초 소셜 인증이면 회원을 바로 생성하지 않는다. 짧은 수명의 일회용 가입 컨텍스트를 Redis에 저장하고, 원문은 전용 HttpOnly 쿠키로 전달한 뒤 닉네임 입력 화면으로 이동시킨다.

```http
HTTP/1.1 302 Found
Set-Cookie: oauth2_signup_token=<one-time-token>; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth/oauth2/signup; Max-Age=<signup-ttl>
Location: <frontend-auth-callback>?result=signup_required
```

동일 이메일의 `LOCAL` 또는 다른 소셜 제공자 회원이 존재하면 자동으로 연결하거나 제공자를 변경하지 않는다. 실패 시 인가 코드, 토큰, 이메일 등 민감 정보를 프론트엔드 리다이렉트 URL에 포함하지 않고 안전한 오류 코드만 전달한다.

```http
HTTP/1.1 302 Found
Location: <frontend-auth-callback>?error=<error-code>
```

**오류 코드:**
- `OAUTH2_AUTHENTICATION_FAILED`
- `OAUTH2_EMAIL_REQUIRED`
- `ACCOUNT_LINK_REQUIRED`
- `ACCOUNT_DISABLED`

### 1.6 소셜 회원가입 완료

```http
POST /api/v1/auth/oauth2/signup
```

- **인증**: `oauth2_signup_token` HttpOnly 쿠키
- **CSRF**: `X-XSRF-TOKEN` 헤더 필요

**요청:**

```json
{
  "nickname": "드롭헌터"
}
```

서버는 일회용 가입 컨텍스트의 제공자, 고유 사용자 식별자 및 검증된 이메일을 사용한다. 클라이언트가 제공자나 이메일을 지정할 수 없다. MVP 회원가입에서는 휴대폰 번호를 받지 않는다.
`nickname`은 1.2.3절의 닉네임 규칙으로 검증한다. 제공자 프로필의 이름·닉네임을 자동으로 사용하지 않는다.


**응답:** `201 Created`

```http
Set-Cookie: oauth2_signup_token=; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth/oauth2/signup; Max-Age=0
Set-Cookie: access_token=<jwt>; HttpOnly; Secure; SameSite=Lax; Path=/api; Max-Age=<access-ttl>
Set-Cookie: refresh_token=<token>; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=<refresh-ttl>
```

```json
{
  "success": true,
  "data": {
    "user": {
      "userId": "6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8",
      "email": "user@example.com",
      "nickname": "드롭헌터",
      "roles": ["USER"]
    }
  }
}
```

가입 컨텍스트는 성공 시 한 번만 소비하고 만료·재사용된 토큰은 거부한다. 소셜 회원가입 시 `password_hash`를 저장하지 않는다.

**오류 코드:**
- `OAUTH2_SIGNUP_CONTEXT_INVALID`
- `OAUTH2_SIGNUP_CONTEXT_EXPIRED`
- `DUPLICATE_EMAIL`
- `INVALID_NICKNAME`
- `DUPLICATE_NICKNAME`

닉네임 오류(`INVALID_NICKNAME`, `DUPLICATE_NICKNAME`)는 가입 컨텍스트를 소비하지 않는다. 사용자는 컨텍스트가 만료되기 전까지 닉네임을 바꿔 다시 요청할 수 있다.

### 1.7 토큰 재발급

```http
POST /api/v1/auth/refresh
```

- **인증**: `refresh_token` HttpOnly 쿠키

**요청:**

요청 본문 없음

유효한 Refresh Token은 한 번 사용한 뒤 폐기하고 새 토큰으로 교체한다.

```http
Set-Cookie: access_token=<new-jwt>; HttpOnly; Secure; SameSite=Lax; Path=/api; Max-Age=<access-ttl>
Set-Cookie: refresh_token=<new-token>; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=<refresh-ttl>
```

**응답:** `204 No Content`

**오류 코드:**
- `INVALID_TOKEN`
- `TOKEN_EXPIRED`

### 1.8 로그아웃

```http
POST /api/v1/auth/logout
```

- **인증**: `refresh_token` HttpOnly 쿠키

**요청:**

요청 본문 없음

**응답:** `204 No Content`

Redis의 Refresh Token을 삭제하고 두 인증 쿠키를 즉시 만료시킨다.

```http
Set-Cookie: access_token=; HttpOnly; Secure; SameSite=Lax; Path=/api; Max-Age=0
Set-Cookie: refresh_token=; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=0
```

### 1.9 내 정보 조회

```http
GET /api/v1/users/me
```

- **인증**: 필요 (`USER`)

**응답:**

```json
{
  "success": true,
  "data": {
    "userId": "6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8",
    "email": "user@example.com",
    "nickname": "드롭헌터",
    "roles": ["USER", "SELLER"],
    "profileImageUrl": "https://cdn.example.com/images/profile.jpg",
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 1.10 내 정보 수정

```http
PATCH /api/v1/users/me
```

- **인증**: 필요 (`USER`)

**요청:**

```json
{
  "nickname": "한정판수집가"
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "userId": "6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8",
    "email": "user@example.com",
    "nickname": "한정판수집가",
    "roles": ["USER", "SELLER"],
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

`nickname`은 1.2.3절의 닉네임 규칙으로 검증한다. 본인의 현재 닉네임과 대소문자만 다른 값으로 바꾸는 것은 중복으로 보지 않는다.

**오류 코드:**
- `INVALID_NICKNAME`
- `DUPLICATE_NICKNAME`

### 1.11 비밀번호 변경

```http
PATCH /api/v1/users/me/password
```

- **인증**: 필요 (`USER`, `LOCAL` 회원)

**요청:**

```json
{
  "currentPassword": "Password123!",
  "newPassword": "NewPassword456!"
}
```

`newPassword`는 1.2.3절의 비밀번호 검증 규칙을 따른다.

**응답:** `204 No Content`

**오류 코드:**
- `INVALID_PASSWORD`
- `PASSWORD_MISMATCH`
- `PASSWORD_NOT_AVAILABLE`

`KAKAO`·`GOOGLE` 회원은 로컬 비밀번호가 없으므로 이 API를 사용할 수 없다.

### 1.12 내 프로필 이미지 등록/수정

```http
PUT /api/v1/users/me/profile-image
```

- **인증**: 필요 (`USER`)

**요청:**

```json
{
  "imageUrl": "https://cdn.example.com/images/profile-uuid.jpg"
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "userId": "6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8",
    "profileImageUrl": "https://cdn.example.com/images/profile-uuid.jpg"
  }
}
```

### 1.13 내 프로필 이미지 삭제

```http
DELETE /api/v1/users/me/profile-image
```

- **인증**: 필요 (`USER`)

**응답:** `204 No Content`

---

