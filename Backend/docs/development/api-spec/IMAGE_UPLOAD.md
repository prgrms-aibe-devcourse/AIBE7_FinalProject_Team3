# 이미지 업로드 API 명세

## 1. 이미지 업로드 API

### 1.1 이미지 업로드 URL 발급

```http
POST /api/v1/uploads/images/presigned-url
```

- **인증**: `SELLER`

**요청:**

```json
{
  "fileName": "shoes.jpg",
  "contentType": "image/jpeg",
  "fileSize": 1048576
}
```

| 필드 | 필수 | 규칙 |
| --- | --- | --- |
| `fileName` | O | 최대 255자. 객체 키에 사용하지 않고 로그에도 남기지 않습니다. |
| `contentType` | O | `image/jpeg`, `image/png`, `image/webp` 중 하나 |
| `fileSize` | O | 0 초과, 최대 5,242,880바이트(5MB) |

**응답:**

```json
{
  "success": true,
  "data": {
    "imageId": "1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b",
    "uploadUrl": "https://<project>.supabase.co/storage/v1/object/upload/sign/drop-images/images/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.jpg?token=<signed-token>",
    "imageUrl": "https://<project>.supabase.co/storage/v1/object/public/drop-images/images/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.jpg",
    "expiresAt": "2026-09-18T16:10:00+09:00"
  }
}
```

**처리 규칙:**
- 경로는 `/presigned-url`을 그대로 유지하고, 응답 의미만 Supabase Storage 기준으로 설명합니다.
- 서버가 UUID를 발급해 객체 키(`images/{imageId}.{확장자}`)로 사용합니다. 원본 파일명은 키에 넣지 않습니다.
- 확장자는 요청 `contentType`에서 결정합니다(`image/jpeg → jpg`, `image/png → png`, `image/webp → webp`). `fileName`의 확장자는 신뢰하지 않습니다.
- `uploadUrl`은 Supabase 서명 업로드 URL이며, 클라이언트가 이 URL로 파일을 직접 업로드(HTTP PUT)합니다. 만료는 Supabase 정책에 따라 발급 시각 + 2시간으로 고정됩니다.
- `imageUrl`은 서버가 만든 공개 버킷 URL입니다. DROP 등록 시 `imageId`와 함께 그대로 다시 사용합니다(2.1·2.4 참고).
- 발급한 `imageId`는 해당 이미지를 DROP에 등록할 때 `drop_images.public_id`로 그대로 저장합니다.
- GALLERY와 DETAIL은 같은 업로드 경로·MIME·파일당 5MB 제한을 사용합니다. 용도와 대체 설명은 업로드 요청이 아니라 DROP 생성·수정 요청(DROP.md 2.1·2.4)에서 지정합니다. DROP별 개수 제한은 GALLERY 최대 5개, DETAIL 최대 15개이며 DROP 생성·수정 시 검증합니다. 파일당 크기 제한이 같으므로 업로드 요청에는 용도를 받지 않습니다.
- 형식·크기의 최종 차단은 버킷 설정(`allowedMimeTypes`, `fileSizeLimit`)이 맡고, 서버 검증은 빠른 400 응답을 위한 1차 방어입니다. 두 값은 위 허용 목록·최대 크기와 같게 유지합니다.

**오류 코드:**
- `VALIDATION_FAILED` — `fileName` 누락·255자 초과, 허용하지 않는 `contentType`, `fileSize` 누락·0 이하·최대 초과. `fieldErrors`의 `field`는 각각 `fileName`, `contentType`, `fileSize`입니다.
- Supabase 호출 실패(타임아웃·4xx·5xx)는 공통 오류 응답으로 변환합니다. Secret Key는 응답·로그·예외 메시지에 포함하지 않습니다. 서명 토큰은 `uploadUrl`에만 포함하며 별도 필드로 응답하거나 로그·예외 메시지에 남기지 않습니다.

> 위 API는 판매자 DROP 이미지 전용입니다. 회원 프로필 이미지는 아래 1.2절의 USER용 경로와 별도 공개 버킷을 사용합니다.

### 1.2 내 프로필 이미지 업로드 URL 발급

```http
POST /api/v1/users/me/profile-image/upload-url
```

- **인증**: 필요 (`USER`, LOCAL·소셜 회원 모두 사용 가능)
- **CSRF**: `X-XSRF-TOKEN` 헤더 필요

**요청:**

```json
{
  "fileName": "profile.jpg",
  "contentType": "image/jpeg",
  "fileSize": 1048576
}
```

요청 필드·필수값·허용 형식·크기는 1.1절과 같습니다. `fileName`은 필수·최대 255자이지만 객체 키와 로그에 사용하지 않습니다.

**응답:** `200 OK`

```json
{
  "success": true,
  "data": {
    "imageId": "1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b",
    "uploadUrl": "https://<project>.supabase.co/storage/v1/object/upload/sign/profile-images/profiles/6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.jpg?token=<signed-token>",
    "imageUrl": "https://<project>.supabase.co/storage/v1/object/public/profile-images/profiles/6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.jpg",
    "expiresAt": "2026-10-22T12:00:00+09:00"
  }
}
```

**처리 규칙:**

- 버킷은 `SUPABASE_PROFILE_IMAGE_BUCKET`으로 지정하며 기본값은 공개 버킷 `profile-images`입니다. DROP 이미지의 `drop-images` 버킷과 구분합니다.
- 버킷은 별도로 생성하고 `allowedMimeTypes`를 `image/jpeg`·`image/png`·`image/webp`, `fileSizeLimit`을 5,242,880바이트(5MB)로 설정합니다. 서버 검증값과 같아야 합니다.
- 서버가 객체 키 `profiles/{본인 public_id}/{새 imageId}.{확장자}`를 만듭니다. 확장자는 1.1절처럼 `contentType`에서 결정합니다.
- 클라이언트는 `uploadUrl`로 Supabase에 직접 HTTP PUT 업로드합니다. URL은 2시간 동안 유효하며, `expiresAt`은 서버 현재 시각 + 2시간입니다.
- URL 발급과 파일 업로드만으로 회원의 프로필 이미지가 등록되지는 않습니다. 업로드 성공 후 응답의 `imageUrl`을 [회원·인증 API 1.12절](MEMBER_AUTH.md#112-내-프로필-이미지-등록수정)의 `PUT /api/v1/users/me/profile-image`에 그대로 보냅니다. 등록 요청에 `imageId`는 보내지 않습니다.
- 등록에 사용하지 않은 업로드는 자동 삭제하지 않습니다. Secret Key·서명 토큰의 응답·로그 처리 규칙은 1.1절과 같습니다.
- 이 경로를 추가해도 기존 `/api/v1/uploads/**`의 `SELLER` 권한 규칙은 바뀌지 않습니다.

**판정 순서:**

인증·권한·CSRF 검사를 통과한 요청에 다음 순서를 적용합니다.

1. 요청 형식·필수값·형식·크기 검증 (`INVALID_REQUEST`·`VALIDATION_FAILED`, 400)
2. 회원 존재·ACTIVE 상태 확인 (`AUTHENTICATION_REQUIRED`, 401)
3. 객체 키 생성 및 Supabase 서명 업로드 URL 발급
4. 업로드 URL·공개 URL·이미지 ID·만료 시각 응답

**오류 코드:**

- `INVALID_REQUEST`(400): 요청 본문 형식 오류
- `VALIDATION_FAILED`(400): `fileName` 누락·공백·255자 초과, `contentType` 누락·공백·허용 형식 아님, `fileSize` 누락·0 이하·5MB 초과. `fieldErrors`의 `field`는 해당 요청 필드입니다.
- `AUTHENTICATION_REQUIRED`(401): `access_token` 쿠키 없음, 회원 없음·정지·탈퇴
- `INVALID_TOKEN`(401): 만료·변조된 Access Token
- `ACCESS_DENIED`(403): `USER` 권한 없음 또는 CSRF 토큰 누락·불일치
- `INTERNAL_SERVER_ERROR`(500): DB 장애 등 서버 오류
- `EXTERNAL_SERVICE_ERROR`(502): Supabase 설정 누락·통신 실패·오류 응답·서명 URL이 없는 응답

---
