# 판매자 API 명세

## 1. 판매자 API

### 1.1 판매자 등록 신청

```http
POST /api/v1/seller-applications
```

- **인증**: `USER`

**요청:**

```json
{
  "brandName": "길동상점",
  "contactEmail": "seller@example.com",
  "description": "한정판 상품 판매점"
}
```

**응답:** `201 Created`

```json
{
  "success": true,
  "data": {
    "applicationId": "9c8b7a65-4321-4fed-9876-1a2b3c4d5e6f",
    "brandName": "길동상점",
    "status": "PENDING",
    "appliedAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 1.2 내 판매자 신청 조회

```http
GET /api/v1/seller-applications/me
```

- **인증**: `USER`

**응답:**

```json
{
  "success": true,
  "data": {
    "applicationId": "9c8b7a65-4321-4fed-9876-1a2b3c4d5e6f",
    "brandName": "길동상점",
    "contactEmail": "seller@example.com",
    "description": "한정판 상품 판매점",
    "status": "PENDING",
    "appliedAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 1.3 판매자 신청 목록 조회

```http
GET /api/v1/admin/seller-applications?status=PENDING&page=0&size=20
```

- **인증**: `ADMIN`

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "applicationId": "9c8b7a65-4321-4fed-9876-1a2b3c4d5e6f",
        "userId": "6f1a2b3c-4d5e-4f70-8192-a3b4c5d6e7f8",
        "brandName": "길동상점",
        "contactEmail": "seller@example.com",
        "status": "PENDING",
        "appliedAt": "2026-09-18T14:00:00+09:00"
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

### 1.4 판매자 신청 승인

```http
POST /api/v1/admin/seller-applications/{applicationId}/approve
```

- **인증**: `ADMIN`

> 승인 시 `sellers.status`를 `APPROVED`로 변경해 유효 `SELLER` 권한을 활성화한다.

**응답:**

```json
{
  "success": true,
  "data": {
    "applicationId": "9c8b7a65-4321-4fed-9876-1a2b3c4d5e6f",
    "status": "APPROVED",
    "approvedAt": "2026-09-18T15:00:00+09:00"
  }
}
```

### 1.5 판매자 신청 반려

```http
POST /api/v1/admin/seller-applications/{applicationId}/reject
```

- **인증**: `ADMIN`

**요청:**

```json
{
  "reason": "사업자 정보 확인이 필요합니다."
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "applicationId": "9c8b7a65-4321-4fed-9876-1a2b3c4d5e6f",
    "status": "REJECTED",
    "rejectedReason": "사업자 정보 확인이 필요합니다.",
    "rejectedAt": "2026-09-18T15:00:00+09:00"
  }
}
```

---

## 2. 판매자 대시보드 API

### 2.1 대시보드 요약

```http
GET /api/v1/seller/dashboard/summary
```

- **인증**: `SELLER`

**쿼리 파라미터:**
- `from`: 시작 일시 (예: `2026-09-01T00:00:00+09:00`)
- `to`: 종료 일시 (예: `2026-09-30T23:59:59+09:00`)

**응답:**

```json
{
  "success": true,
  "data": {
    "dropCounts": {
      "DRAFT": 2,
      "WISH": 3,
      "GRAB": 1,
      "ENDED": 5,
      "CANCELED": 1
    },
    "orderCounts": {
      "PAYMENT_PENDING": 4,
      "PAID": 10,
      "PREPARING": 3,
      "SHIPPED": 7,
      "CANCELED": 2
    },
    "paymentCounts": {
      "SUCCEEDED": 20,
      "FAILED": 3,
      "UNKNOWN": 1
    },
    "reconciliationRequired": 1,
    "stockSummary": {
      "available": 100,
      "reserved": 10,
      "sold": 50
    }
  }
}
```

### 2.2 DROP별 통계

```http
GET /api/v1/seller/dashboard/drops?page=0&size=20
```

- **인증**: `SELLER`

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "dropId": 100,
        "name": "한정판 스니커즈",
        "status": "GRAB",
        "activeWishCount": 152,
        "availableStock": 4,
        "reservedStock": 2,
        "soldStock": 14,
        "orderCount": 10,
        "salesAmount": 1806000
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

### 2.3 임박 DROP 조회

```http
GET /api/v1/seller/dashboard/upcoming-drops?eventType=START&withinMinutes=60
```

- **인증**: `SELLER`

**쿼리 파라미터:**
- `eventType`: `START`(판매 시작 임박) 또는 `END`(판매 종료 임박), 기본값 `START`
- `withinMinutes`: `60`(1시간) 또는 `1440`(1일), 기본값 `60`

현재 시각보다 뒤이고 선택한 기간 이내에 일정이 있는 본인 DROP을 임박 시각 오름차순으로 반환합니다. 시작 임박은 `WISH` 상태의 `saleStartsAt`, 종료 임박은 `GRAB` 상태의 `saleEndsAt`을 기준으로 합니다. 이미 시작하거나 종료된 일정은 제외합니다.

**응답:**

```json
{
  "success": true,
  "data": [
    {
      "dropId": 100,
      "name": "한정판 스니커즈",
      "status": "WISH",
      "eventType": "START",
      "upcomingAt": "2026-09-18T17:00:00+09:00"
    }
  ]
}
```

`dropId`로 기존 판매자 DROP 상세 조회 API를 호출할 수 있습니다.

---

