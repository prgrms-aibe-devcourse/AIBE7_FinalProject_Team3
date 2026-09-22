# 회원 및 인증 API 명세

## 1. 회원 및 인증 API

### 1.1 회원가입

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

**오류 코드:**
- `INVALID_EMAIL`
- `DUPLICATE_EMAIL`
- `INVALID_PASSWORD`

### 1.2 로그인

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

```json
{
  "success": true,
  "data": {
    "tokenType": "Bearer",
    "accessToken": "access-token",
    "refreshToken": "refresh-token",
    "expiresIn": 3600,
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

### 1.3 토큰 재발급

```http
POST /api/v1/auth/refresh
```

- **인증**: Refresh Token

**요청:**

```json
{
  "refreshToken": "refresh-token"
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "tokenType": "Bearer",
    "accessToken": "new-access-token",
    "refreshToken": "new-refresh-token",
    "expiresIn": 3600
  }
}
```

**오류 코드:**
- `INVALID_TOKEN`
- `TOKEN_EXPIRED`

### 1.4 로그아웃

```http
POST /api/v1/auth/logout
```

- **인증**: 필요

**요청:**

```json
{
  "refreshToken": "refresh-token"
}
```

**응답:** `204 No Content`

### 1.5 내 정보 조회

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

### 1.6 내 정보 수정

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

### 1.7 비밀번호 변경

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

### 1.8 내 프로필 이미지 등록/수정

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

### 1.9 내 프로필 이미지 삭제

```http
DELETE /api/v1/users/me/profile-image
```

- **인증**: 필요 (`USER`)

**응답:** `204 No Content`

---

