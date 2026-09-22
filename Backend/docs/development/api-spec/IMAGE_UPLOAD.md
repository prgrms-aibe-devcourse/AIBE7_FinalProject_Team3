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

**응답:**

```json
{
  "success": true,
  "data": {
    "imageId": "1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b",
    "uploadUrl": "https://storage.example.com/presigned-url",
    "imageUrl": "https://cdn.example.com/images/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.jpg",
    "expiresAt": "2026-09-18T14:10:00+09:00"
  }
}
```

**처리 규칙:**
- 서버가 UUID를 발급해 객체 키(`images/{imageId}.{확장자}`)로 사용합니다. 원본 파일명은 키에 넣지 않습니다.
- 발급한 `imageId`는 해당 이미지를 DROP에 등록할 때 `drop_images.public_id`로 그대로 저장합니다.
- 회원 프로필 이미지는 `users.public_id`를 객체 키로 사용합니다.

---

