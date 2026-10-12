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
- GALLERY와 DETAIL은 같은 업로드 경로·MIME·파일당 5MB 제한을 사용합니다. 용도와 대체 설명은 업로드 요청이 아니라 DROP 생성·수정 요청(DROP.md 2.1·2.4)에서 지정합니다. 두 용도의 DROP별 합계는 최대 10개입니다.
- 형식·크기의 최종 차단은 버킷 설정(`allowedMimeTypes`, `fileSizeLimit`)이 맡고, 서버 검증은 빠른 400 응답을 위한 1차 방어입니다. 두 값은 위 허용 목록·최대 크기와 같게 유지합니다.

**오류 코드:**
- `VALIDATION_FAILED` — `fileName` 누락·255자 초과, 허용하지 않는 `contentType`, `fileSize` 누락·0 이하·최대 초과. `fieldErrors`의 `field`는 각각 `fileName`, `contentType`, `fileSize`입니다.
- Supabase 호출 실패(타임아웃·4xx·5xx)는 공통 오류 응답으로 변환합니다. Secret Key와 서명 토큰은 응답·로그·예외 메시지에 포함하지 않습니다.

> 회원 프로필 이미지는 이 API 범위 밖이며, 추후 별도 경로·버킷으로 다룹니다.

---
