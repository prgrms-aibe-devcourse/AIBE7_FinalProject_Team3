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
- 쿠키가 없거나 알 수 없는·만료된·이미 사용한 토큰이면 `EMAIL_SIGNUP_CONTEXT_INVALID`로 거부한다. 만료된 컨텍스트는 Redis에서 삭제되어 없는 컨텍스트와 구분할 수 없고 쿠키도 같은 시각에 만료되므로, 만료를 별도 코드로 구분하지 않는다.
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
- **CSRF**: `X-XSRF-TOKEN` 헤더 필요

**요청:**

```json
{
  "email": "user@example.com",
  "password": "Password123!"
}
```

요청 이메일은 1.2절과 동일한 규칙으로 정규화한 뒤 회원을 조회한다.

**응답:** `200 OK`

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

- `roles`는 Access Token의 권한과 같은 값이다(COMMON 2.1). 활성 회원은 모두 `USER`를 가지며, `ADMIN`·승인된 판매자(`SELLER`)는 추가 권한으로 함께 담긴다.
- 로그인은 `XSRF-TOKEN`을 새로 발급하지 않는다. 로그인 전에 받은 CSRF 토큰을 그대로 사용한다.

**검증 규칙:**
- `email`, `password`는 필수다. 필수값이 없으면 `VALIDATION_FAILED`로 거부하고, 없는 항목 전부를 `fieldErrors`로 반환한다.
  - `email`: 키 누락, `null`, 빈 문자열, 앞뒤 공백 제거 후 빈 문자열인 경우
  - `password`: 키 누락, `null`, 빈 문자열인 경우. 비밀번호는 trim하거나 가공하지 않는다.
- 이메일 형식과 비밀번호 규칙(1.2.3)은 로그인에서 검사하지 않는다. 규칙이 바뀌어도 기존 회원이 로그인할 수 있어야 하며, 규칙에 맞지 않는 값은 일치하는 회원이 없어 `INVALID_CREDENTIALS`가 된다.
- 요청 본문이 없거나 JSON 형식이 잘못되면 `INVALID_REQUEST`로 거부한다.

**판정 순서:**
1. 정규화한 이메일로 회원을 조회한다.
2. 회원이 없거나 `LOCAL` 회원이 아니면(소셜 회원은 비밀번호가 없다) `INVALID_CREDENTIALS`로 거부한다.
3. 비밀번호가 일치하지 않으면 계정 상태와 관계없이 `INVALID_CREDENTIALS`로 거부한다.
4. 비밀번호가 일치해도 계정 상태가 `ACTIVE`가 아니면(`SUSPENDED`, `WITHDRAWN`) `ACCOUNT_DISABLED`로 거부한다.

- 2·3의 응답은 같으며, 서버는 회원이 없는 경우에도 비밀번호 해시 검증을 수행해 응답 시간으로 계정 존재 여부가 드러나지 않게 한다.
- 계정 상태는 비밀번호를 아는 사용자에게만 드러난다.
- 실패 응답에는 인증 쿠키를 발급하지 않고 Refresh Token을 저장하지 않는다.

**오류 코드:**
- `INVALID_REQUEST`
- `VALIDATION_FAILED`
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

- 두 제공자 모두 OpenID Connect로 인증한다. 요청 스코프는 GOOGLE `openid email`, KAKAO `openid account_email`이며 제공자 프로필(이름·닉네임·사진)은 받지 않는다.
- 인가 요청(`state`, `nonce`, PKCE `code_verifier`)은 로그인 왕복 동안 서버 세션에 저장하고 콜백 처리 후 폐기한다. PKCE는 `S256`이다. 로그인 상태는 세션이 아니라 1.3절의 인증 쿠키로만 판단한다.
- `GET`이면서 `provider`가 `kakao` 또는 `google`일 때만 소셜 로그인을 시작한다. 그 밖의 `provider`는 `UNSUPPORTED_OAUTH2_PROVIDER`(400)로 거부한다.

**오류 코드:**
- `UNSUPPORTED_OAUTH2_PROVIDER`

### 1.5 소셜 로그인 콜백

```http
GET /api/v1/auth/oauth2/{provider}/callback
```

- **인증**: 불필요
- **호출 주체**: KAKAO 또는 GOOGLE OAuth2 제공자

클라이언트가 직접 호출하는 API가 아니다. 서버는 인가 코드와 `state`를 검증하고, ID 토큰(서명·`nonce`)을 검증해 제공자의 고유 사용자 식별자(`sub`)와 검증된 이메일을 얻는다. 제공자 이메일은 1.2절과 동일한 규칙으로 정규화한다.

- 검증된 이메일은 `email_verified`가 `true`인 `email`이다. 이메일이 없거나 검증되지 않았으면 `OAUTH2_EMAIL_REQUIRED`로 거부한다.
- GOOGLE은 ID 토큰의 `email`·`email_verified`를 쓴다. KAKAO는 ID 토큰에 `email_verified`가 없으므로 토큰 교환에서 받은 Access Token으로 OIDC userinfo를 조회해 `email`·`email_verified`를 받는다. userinfo의 `sub`가 ID 토큰의 `sub`와 다르면 그 응답을 쓰지 않고 거부한다.

**판정 순서:**
1. 제공자와 고유 사용자 식별자로 회원을 조회한다. 있으면 계정 상태가 `ACTIVE`인지 확인하고(아니면 `ACCOUNT_DISABLED`) 로그인시킨다.
   - 제공자 쪽 이메일이 바뀌었어도 같은 회원이다. 저장된 이메일은 갱신하지 않는다.
2. 없으면 정규화한 이메일로 회원을 조회한다. 같은 이메일의 회원(`LOCAL`, 다른 제공자, 같은 제공자의 다른 식별자)이 있으면 `ACCOUNT_LINK_REQUIRED`로 거부한다.
3. 없으면 가입 컨텍스트를 발급하고 닉네임 입력 화면으로 이동시킨다.

- 계정 상태는 제공자 인증이 성공한 뒤에만 판정한다. 로그인·거부 모두 기존 회원 데이터를 변경하지 않는다.

**프론트엔드 콜백 주소:** 서버 설정으로 관리한다. 결과는 `result` 또는 `error` 쿼리 하나만 붙인다. 프론트엔드가 해시 라우터를 쓰므로 쿼리는 해시 뒤에 붙는다(예: `http://localhost:5173/#/auth/callback?result=success`).

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
Set-Cookie: oauth2_signup_token=<one-time-token>; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth/oauth2/signup; Max-Age=900
Location: <frontend-auth-callback>?result=signup_required
```

가입 컨텍스트에는 제공자, 고유 사용자 식별자, 정규화한 이메일을 담는다. 발급 후 15분간 유효하며 토큰 원문이 아닌 해시로 Redis에 저장한다.

동일 이메일의 `LOCAL` 또는 다른 소셜 제공자 회원이 존재하면 자동으로 연결하거나 제공자를 변경하지 않는다. 실패 시 인가 코드, 토큰, 이메일 등 민감 정보를 프론트엔드 리다이렉트 URL에 포함하지 않고 안전한 오류 코드만 전달한다.

```http
HTTP/1.1 302 Found
Location: <frontend-auth-callback>?error=<error-code>
```

**오류 코드:** (`error` 쿼리로 전달)
- `OAUTH2_AUTHENTICATION_FAILED`: 사용자 취소, `state` 불일치, 인가 코드·토큰 교환 실패, ID 토큰 검증 실패, userinfo 조회 실패·`sub` 불일치, 지원하지 않는 제공자
- `OAUTH2_EMAIL_REQUIRED`
- `ACCOUNT_LINK_REQUIRED`
- `ACCOUNT_DISABLED`
- `INTERNAL_SERVER_ERROR`: Redis·DB 장애 등 서버 오류. 인증 실패가 아니다

실패 시 인증 쿠키와 가입 컨텍스트 쿠키를 발급하지 않는다.

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

소셜 회원가입 시 `password_hash`를 저장하지 않고 제공자와 고유 사용자 식별자를 저장한다. 회원은 `USER`·`ACTIVE`로 생성한다.

**가입 컨텍스트:**
- 요청 본문을 검증하기 전에 가입 컨텍스트를 먼저 확인한다.
- 쿠키가 없거나 알 수 없는·만료된·이미 사용한·조작된 토큰이면 `OAUTH2_SIGNUP_CONTEXT_INVALID`로 거부한다. 만료된 컨텍스트는 Redis에서 삭제되어 없는 컨텍스트와 구분할 수 없으므로 만료를 별도 코드로 구분하지 않는다(1.2.3과 같음).
- 가입에 성공하거나 `DUPLICATE_EMAIL`(콜백 이후 같은 이메일의 회원이 생김)이면 컨텍스트를 소비한다.
- 요청 값 오류(`INVALID_REQUEST`, `VALIDATION_FAILED`, `INVALID_NICKNAME`)와 `DUPLICATE_NICKNAME`은 컨텍스트를 소비하지 않는다. 사용자는 컨텍스트가 만료되기 전까지 닉네임을 바꿔 다시 요청할 수 있다.

**오류 코드:**
- `OAUTH2_SIGNUP_CONTEXT_INVALID`
- `INVALID_REQUEST`
- `VALIDATION_FAILED`
- `DUPLICATE_EMAIL`
- `INVALID_NICKNAME`
- `DUPLICATE_NICKNAME`

### 1.7 토큰 재발급

```http
POST /api/v1/auth/refresh
```

- **인증**: `refresh_token` HttpOnly 쿠키
- **CSRF**: `X-XSRF-TOKEN` 헤더 필요

**요청:**

요청 본문 없음

**응답:** `204 No Content`

```http
Set-Cookie: access_token=<new-jwt>; HttpOnly; Secure; SameSite=Lax; Path=/api; Max-Age=<access-ttl>
Set-Cookie: refresh_token=<new-token>; HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=<refresh-ttl>
```

- 유효한 Refresh Token은 한 번만 사용할 수 있다. 성공하면 새 Access Token과 Refresh Token으로 교체하고 사용한 Refresh Token은 폐기한다. 두 토큰은 응답 본문에 포함하지 않는다.
- 새 Refresh Token은 로그인 때 시작한 세션을 이어받는다. 재발급할 때마다 비활동 만료는 다시 시작되지만 로그인 시점 기준의 절대 만료는 늘어나지 않는다. `refresh-ttl`은 둘 중 먼저 오는 시각까지 남은 초이며, 절대 만료가 지난 세션은 재발급하지 않는다.
- 재발급할 때 회원 상태와 권한을 다시 확인한다. 계정이 `ACTIVE`가 아니면 거부하고 그 세션의 Refresh Token을 폐기한다. 판매자 승인·취소는 새 Access Token의 권한에 반영된다.
- 같은 Refresh Token으로 동시에 요청하면 하나만 성공하고 나머지는 `INVALID_TOKEN`이다.
- 이미 교체된 Refresh Token이 다시 사용되면 탈취로 보고 그 세션의 Refresh Token을 폐기한다. 단, 교체 직후 짧은 유예 시간(서버 설정, 기본 10초) 안의 재사용은 여러 탭의 동시 재발급으로 보고 세션을 유지한 채 요청만 거부한다.
- 재발급은 `XSRF-TOKEN`을 새로 발급하지 않는다.

**오류 코드:**
- `INVALID_TOKEN`

Redis는 만료된 키를 삭제하므로 만료·로그아웃·교체·위조를 구분할 수 없다. 재발급 실패는 사유와 관계없이 `INVALID_TOKEN`으로 응답한다. Redis 장애 등 서버 오류는 `INTERNAL_SERVER_ERROR`(500)이며 인증 실패가 아니므로 클라이언트는 로그아웃하지 않는다(COMMON 3절).

### 1.8 로그아웃

```http
POST /api/v1/auth/logout
```

- **인증**: `refresh_token` HttpOnly 쿠키 (선택)

**요청:**

요청 본문 없음

**응답:** `204 No Content`

Redis의 Refresh Token을 삭제하고 두 인증 쿠키를 즉시 만료시킨다.

- Access Token은 요구하지 않는다. Access Token이 없거나 만료·변조된 상태에서도 로그아웃할 수 있으며, 정리할 세션은 `refresh_token` 쿠키로 찾는다.
- 폐기 범위는 쿠키의 Refresh Token이 속한 로그인 세션 하나다. 같은 사용자의 다른 세션은 유지된다.
- 이미 재발급으로 교체된 Refresh Token으로 로그아웃해도 그 토큰이 속한 세션의 현재 Refresh Token을 폐기한다. 재발급과 로그아웃이 경합하거나, 탈취된 토큰으로 먼저 재발급된 경우에도 로그아웃으로 세션을 끊을 수 있다.
- Access Token은 서버에 저장하지 않는 JWT라서 로그아웃으로 무효화하지 않는다. `access_token` 쿠키를 만료시켜 브라우저가 더 이상 보내지 않게 하지만, 로그아웃 전에 복사된 값은 만료 시각(발급 후 `<access-ttl>`, 서버 설정, 기본 15분)까지 유효할 수 있다. 로그아웃 후 인증 유지는 Refresh Token 세션 폐기로 막는다.
- 결과는 멱등하다. `refresh_token` 쿠키가 없거나 빈 값이거나, 이미 로그아웃·교체·만료됐거나 발급한 적 없는 값이어도 같은 `204`와 두 만료 쿠키를 응답한다.
- 다른 상태 변경 요청과 같이 CSRF 토큰이 필요하다. CSRF 검증에 실패하면 `403 ACCESS_DENIED`이며 Refresh Token과 인증 쿠키는 그대로 유지된다. 로그아웃은 `XSRF-TOKEN`을 교체하거나 삭제하지 않는다.

Redis 장애 등으로 Refresh Token 폐기를 확인하지 못하면 `INTERNAL_SERVER_ERROR`(500)로 응답하고 인증 쿠키를 만료시키지 않는다. 서버에 토큰이 남아 있을 수 있으므로 쿠키를 유지해 클라이언트가 같은 요청으로 재시도할 수 있게 한다. 폐기는 멱등이므로 재시도해도 안전하다.

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
    "provider": "LOCAL",
    "roles": ["USER", "SELLER"],
    "profileImageUrl": "https://cdn.example.com/images/profile.jpg",
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

- 사용자 ID는 경로·쿼리로 받지 않는다. 항상 `access_token`의 본인 정보를 돌려준다.
- `provider`는 가입 방식(`LOCAL`, `KAKAO`, `GOOGLE`)이다. `LOCAL` 회원만 비밀번호 변경(1.11)을 사용할 수 있다.
- `roles`는 요청 Access Token의 권한이며 로그인 응답과 같은 값·순서(`USER`, `SELLER`, `ADMIN`)다. 판매자 승인·취소는 다음 재발급(1.7)부터 반영된다. 즉시 반영하려면 재발급한 뒤 다시 조회한다.
- `profileImageUrl`은 등록된 이미지가 없으면 `null`이다. 키는 생략하지 않는다.
- `createdAt`은 ISO 8601 형식이다. 같은 시각을 다른 오프셋(예: `Z`)으로 표기할 수 있다.
- 로그인 후 정지·탈퇴된 회원은 토큰이 유효해도 `AUTHENTICATION_REQUIRED`(401)로 거부한다. 계정 상태는 드러내지 않으며 로그인(1.3)에서 `ACCOUNT_DISABLED`로 안내된다. 클라이언트는 다른 401과 같이 재발급을 시도하고, 재발급이 세션을 폐기해 비로그인 상태가 된다.

**오류 코드:**
- `AUTHENTICATION_REQUIRED`: `access_token` 쿠키 없음, 회원 없음·정지·탈퇴
- `INVALID_TOKEN`: 만료·변조된 Access Token
- `ACCESS_DENIED`: `USER` 권한이 없는 토큰

### 1.10 내 정보 수정

```http
PATCH /api/v1/users/me
```

- **인증**: 필요 (`USER`)
- **CSRF**: `X-XSRF-TOKEN` 헤더 필요

**요청:**

```json
{
  "nickname": "한정판수집가"
}
```

**응답:** `200 OK`

```json
{
  "success": true,
  "data": {
    "userId": "6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8",
    "email": "user@example.com",
    "nickname": "한정판수집가",
    "provider": "LOCAL",
    "roles": ["USER", "SELLER"],
    "profileImageUrl": "https://cdn.example.com/images/profile.jpg",
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

- 닉네임만 수정할 수 있다. 사용자 ID는 요청으로 받지 않고 `access_token`의 본인 정보를 수정한다. 이메일은 변경하지 않는다.
- 응답은 1.9 내 정보 조회와 같은 `UserMeResponse` 7개 필드다. `roles`는 요청 Access Token의 권한이며, `profileImageUrl`은 없으면 `null`이고 키를 생략하지 않는다. 클라이언트는 응답으로 내 정보 캐시를 교체할 수 있다.
- `nickname`은 앞뒤 공백을 제거(`strip`)한 뒤 1.2.3절의 닉네임 규칙으로 검증하고 저장한다.
- 공백을 제거한 값이 현재 닉네임과 대소문자까지 같으면 `NICKNAME_UNCHANGED`(400)로 거부한다. 예를 들어 현재 닉네임이 `Grab`이면 `Grab`과 `" Grab "`은 거부하지만 `grab`은 변경으로 본다.
- 중복 검사는 본인을 제외하고 대소문자를 무시한다. 다른 회원과 중복되면 `DUPLICATE_NICKNAME`(409)이며, 동시 변경으로 DB 유니크 제약을 위반한 경우도 같은 코드로 응답한다.

**판정 순서:**

인증·권한·CSRF 검사를 통과한 요청에 다음 순서를 적용한다.

1. 요청 형식·닉네임 규칙 검증 (`INVALID_REQUEST`·`VALIDATION_FAILED`·`INVALID_NICKNAME`, 400)
2. 회원 존재·ACTIVE 상태 확인 (`AUTHENTICATION_REQUIRED`, 401)
3. 현재 닉네임과 같은지 확인 (`NICKNAME_UNCHANGED`, 400)
4. 다른 회원의 닉네임과 중복인지 확인 (`DUPLICATE_NICKNAME`, 409)
5. 저장 후 변경된 내 정보 응답

**오류 코드:**

- `INVALID_REQUEST`(400): 요청 본문 형식 오류
- `VALIDATION_FAILED`(400): 닉네임 누락·`null` 또는 앞뒤 공백 제거 후 빈 값
- `INVALID_NICKNAME`(400): 닉네임 규칙 위반
- `NICKNAME_UNCHANGED`(400): 현재 닉네임과 같은 값
- `DUPLICATE_NICKNAME`(409): 다른 회원이 사용하는 닉네임 (대소문자 무시)
- `AUTHENTICATION_REQUIRED`(401): `access_token` 쿠키 없음, 회원 없음·정지·탈퇴
- `INVALID_TOKEN`(401): 만료·변조된 Access Token
- `ACCESS_DENIED`(403): `USER` 권한 없음 또는 CSRF 토큰 누락·불일치
- `INTERNAL_SERVER_ERROR`(500): DB 장애 등 서버 오류

### 1.11 비밀번호 변경

```http
PATCH /api/v1/users/me/password
```

- **인증**: 필요 (`USER`, `LOCAL` 회원)
- **CSRF**: `X-XSRF-TOKEN` 헤더 필요

**요청:**

```json
{
  "currentPassword": "Password123!",
  "newPassword": "NewPassword456!"
}
```

- 두 필드는 필수이며 누락·`null`·빈 문자열이면 `VALIDATION_FAILED`(400)로 거부한다. 두 값 모두 앞뒤 공백을 제거하거나 가공하지 않는다.
- `currentPassword`는 필수값만 검사하고 1.2.3절의 비밀번호 규칙을 적용하지 않는다. 비밀번호 규칙이 바뀌어도 기존 비밀번호로 변경할 수 있어야 하기 때문이다.
- `newPassword`는 1.2.3절의 비밀번호 검증 규칙을 따른다. 공백만 있는 값도 규칙 위반으로 `INVALID_PASSWORD`(400)다.
- `KAKAO`·`GOOGLE` 회원은 로컬 비밀번호가 없으므로 이 API를 사용할 수 없으며 `PASSWORD_NOT_AVAILABLE`(400)로 거부한다.
- 현재 비밀번호가 맞는지 먼저 확인한 뒤 새 비밀번호와 현재 비밀번호의 원문을 대소문자 구분하여 비교한다. 같으면 `PASSWORD_UNCHANGED`(400)로 거부한다. 현재 비밀번호가 틀리면 두 입력이 같아도 `PASSWORD_MISMATCH`(400)가 우선한다.

**응답:** `204 No Content`

비밀번호 변경은 기존 로그인 세션을 종료하지 않는다. 이 요청의 세션과 다른 기기의 세션을 모두 유지하며 기존 Refresh Token으로 재발급할 수 있다. 이미 로그인한 타인의 세션도 비밀번호 변경만으로 폐기되지 않는다.

**판정 순서:**

인증·권한·CSRF 검사를 통과한 요청에 다음 순서를 적용한다.

1. 요청 형식·필수값·새 비밀번호 규칙 검증 (`INVALID_REQUEST`·`VALIDATION_FAILED`·`INVALID_PASSWORD`, 400)
2. 회원 존재·ACTIVE 상태 확인 (`AUTHENTICATION_REQUIRED`, 401)
3. 비밀번호를 사용하는 LOCAL 회원인지 확인 (`PASSWORD_NOT_AVAILABLE`, 400)
4. 현재 비밀번호 검증 (`PASSWORD_MISMATCH`, 400)
5. 새 비밀번호가 현재 비밀번호와 같은지 확인 (`PASSWORD_UNCHANGED`, 400)
6. 새 비밀번호 저장 후 본문 없이 204 응답

현재 비밀번호 검증 후 저장 전에 다른 요청이 먼저 비밀번호를 바꿨다면 변경하지 않고 `PASSWORD_MISMATCH`(400)로 응답한다. 이 API에는 현재 비밀번호 확인 실패에 대한 별도의 횟수 제한을 두지 않는다.

**오류 코드:**

- `INVALID_REQUEST`(400): 요청 본문 형식 오류
- `VALIDATION_FAILED`(400): 필수값 누락·`null`·빈 문자열
- `INVALID_PASSWORD`(400): 새 비밀번호 규칙 위반
- `PASSWORD_NOT_AVAILABLE`(400): 비밀번호를 사용하지 않는 계정
- `PASSWORD_MISMATCH`(400): 현재 비밀번호 불일치 또는 검증 후 저장 전 다른 요청이 비밀번호를 변경함
- `PASSWORD_UNCHANGED`(400): 새 비밀번호가 현재 비밀번호와 같음 (현재 비밀번호 검증 성공 후 판단)
- `AUTHENTICATION_REQUIRED`(401): `access_token` 쿠키 없음, 회원 없음·정지·탈퇴
- `INVALID_TOKEN`(401): 만료·변조된 Access Token
- `ACCESS_DENIED`(403): `USER` 권한 없음 또는 CSRF 토큰 누락·불일치
- `INTERNAL_SERVER_ERROR`(500): DB 장애·비밀번호 해시 생성 실패 등 서버 오류

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

