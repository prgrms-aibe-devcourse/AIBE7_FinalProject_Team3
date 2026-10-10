# WISH API 명세

## 1. WISH API

### 1.1 WISH 등록

```http
PUT /api/v1/drops/{dropId}/wish
```

- **인증**: 필요 (`USER`)

> 동일한 요청을 반복해도 활성 WISH는 하나만 존재합니다.
>
> 이미 활성 WISH가 있으면 저장하지 않고 기존 `wishedAt`을 그대로 반환하며, 취소 후 재등록할 때만 `wishedAt`이 갱신됩니다.

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "wished": true,
    "wishedAt": "2026-09-18T14:00:00+09:00",
    "notice": "WISH는 구매, 재고 예약 또는 구매 우선권을 보장하지 않습니다."
  }
}
```

**오류 코드:**

| DROP 상태·시각 | 오류 코드 | HTTP |
| --- | --- | --- |
| 없음, `DRAFT` | `DROP_NOT_FOUND` | 404 |
| `WISH`, 현재 시각 ≥ `saleStartsAt` | `GRAB_ALREADY_STARTED` | 409 |
| `GRAB`, `ENDED` | `GRAB_ALREADY_STARTED` | 409 |
| `CANCELED` | `DROP_NOT_WISHABLE` | 409 |

> `WISH`이고 현재 시각이 `saleStartsAt` 전이면 등록할 수 있습니다.

### 1.2 WISH 취소

```http
DELETE /api/v1/drops/{dropId}/wish
```

- **인증**: 필요 (`USER`)

**응답:** `204 No Content`

> 활성 WISH가 없거나 이미 취소된 경우에도 `204`를 반환합니다(멱등).

**오류 코드:**

| DROP 상태·시각 | 오류 코드 | HTTP |
| --- | --- | --- |
| 없음, `DRAFT` | `DROP_NOT_FOUND` | 404 |
| `WISH`, 현재 시각 ≥ `saleStartsAt` | `GRAB_ALREADY_STARTED` | 409 |
| `GRAB`, `ENDED` | `GRAB_ALREADY_STARTED` | 409 |
| `CANCELED` | `DROP_NOT_WISHABLE` | 409 |

> GRAB 시작 이후에는 취소할 수 없습니다. 등록과 취소의 판정 규칙은 같습니다.

### 1.3 내 WISH 목록

```http
GET /api/v1/users/me/wishes?page=0&size=20
```

- **인증**: 필요 (`USER`)

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "dropId": 100,
        "name": "한정판 스니커즈",
        "thumbnailUrl": "https://example.com/image.jpg",
        "minPrice": 129000,
        "status": "WISH",
        "wishedAt": "2026-09-18T14:00:00+09:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  }
}
```

`thumbnailUrl`은 GALLERY의 첫 이미지이며 DETAIL 이미지는 사용하지 않습니다.

### 1.4 DROP 활성 WISH 수 조회

```http
GET /api/v1/seller/drops/{dropId}/wish-count
```

- **인증**: `SELLER` (소유권 필요)

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "activeWishCount": 152
  }
}
```

---
