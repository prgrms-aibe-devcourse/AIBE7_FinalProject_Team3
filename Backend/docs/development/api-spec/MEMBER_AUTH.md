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

### 1.2 회원가입

```http
POST /api/v1/auth/signup
```

- **인증**: 불필요

**요청:**

```json
{
  "email": "user@example.com",
  "password": "Password123!",
  "displayName": "홍길동",
  "phone": "01012345678"
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
- 이메일은 유효한 형식이어야 한다.
- 이메일은 중복될 수 없다.
- 비밀번호는 최소 8자 이상이어야 한다.
- 비밀번호는 영문, 숫자, 특수문자를 포함해야 한다.
- 비밀번호는 Argon2id로 해시해 저장한다.

**오류 코드:**
- `INVALID_EMAIL`
- `DUPLICATE_EMAIL`
- `INVALID_PASSWORD`

### 1.3 로그인

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

### 1.4 토큰 재발급

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

### 1.5 로그아웃

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

### 1.6 내 정보 조회

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
    "phone": "01012345678",
    "roles": ["USER", "SELLER"],
    "profileImageUrl": "https://cdn.example.com/images/profile.jpg",
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 1.7 내 정보 수정

```http
PATCH /api/v1/users/me
```

- **인증**: 필요 (`USER`)

**요청:**

```json
{
  "displayName": "김길동",
  "phone": "01098765432"
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
    "phone": "01098765432",
    "roles": ["USER", "SELLER"],
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 1.8 비밀번호 변경

```http
PATCH /api/v1/users/me/password
```

- **인증**: 필요 (`USER`)

**요청:**

```json
{
  "currentPassword": "Password123!",
  "newPassword": "NewPassword456!"
}
```

**응답:** `204 No Content`

**오류 코드:**
- `INVALID_PASSWORD`
- `PASSWORD_MISMATCH`

### 1.9 내 프로필 이미지 등록/수정

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

### 1.10 내 프로필 이미지 삭제

```http
DELETE /api/v1/users/me/profile-image
```

- **인증**: 필요 (`USER`)

**응답:** `204 No Content`

---

