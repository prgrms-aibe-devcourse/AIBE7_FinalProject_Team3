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

```http
POST /api/v1/auth/signup
```

- **인증**: 불필요

이 API는 이메일과 비밀번호를 사용하는 `LOCAL` 회원가입 전용이다. KAKAO·GOOGLE 회원가입은 1.4~1.6절의 OAuth2 흐름을 사용한다.

**요청:**

```json
{
  "email": "user@example.com",
  "password": "Password123!",
  "displayName": "홍길동"
}
```

**응답:** `201 Created`

```json
{
  "success": true,
  "data": {
    "userId": "6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8",
    "email": "user@example.com",
    "displayName": "홍길동",
    "roles": ["USER"],
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

**검증 규칙:**
- MVP 회원가입에서는 휴대폰 번호를 받지 않는다.
- 이메일은 앞뒤 공백을 제거하고 전체를 소문자로 변환해 정규화한 뒤 검증·중복 검사·저장한다.
- 정규화 후 중간에 공백이 포함되면 `INVALID_EMAIL`로 거부한다.
- 이메일은 유효한 형식이어야 한다.
- 이메일은 중복될 수 없다.
- 비밀번호는 최소 8자 이상, 최대 64자 이하여야 한다.
- 비밀번호는 영문, 숫자, 특수문자를 포함해야 한다.
- 비밀번호에 공백 문자가 포함되면 `INVALID_PASSWORD`로 거부한다. 서버는 비밀번호를 trim하거나 가공하지 않는다.
- 비밀번호는 Argon2id로 해시해 저장한다.

**오류 코드:**
- `INVALID_EMAIL`
- `DUPLICATE_EMAIL`
- `INVALID_PASSWORD`

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
      "displayName": "홍길동",
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

최초 소셜 인증이면 회원을 바로 생성하지 않는다. 짧은 수명의 일회용 가입 컨텍스트를 Redis에 저장하고, 원문은 전용 HttpOnly 쿠키로 전달한 뒤 표시 이름 입력 화면으로 이동시킨다.

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
  "displayName": "홍길동"
}
```

서버는 일회용 가입 컨텍스트의 제공자, 고유 사용자 식별자 및 검증된 이메일을 사용한다. 클라이언트가 제공자나 이메일을 지정할 수 없다. MVP 회원가입에서는 휴대폰 번호를 받지 않는다.


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
      "displayName": "홍길동",
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
- `INVALID_DISPLAY_NAME`

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
    "displayName": "홍길동",
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
  "displayName": "김길동"
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "userId": "6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8",
    "email": "user@example.com",
    "displayName": "김길동",
    "roles": ["USER", "SELLER"],
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

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

`newPassword`는 1.2절의 비밀번호 검증 규칙을 따른다.

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

