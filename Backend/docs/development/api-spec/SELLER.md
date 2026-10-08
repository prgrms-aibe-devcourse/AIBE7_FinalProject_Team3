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
- `from`: 시작 일시 (예: `2026-09-01T00:00:00+09:00`), 생략 시 제한 없음
- `to`: 종료 일시 (예: `2026-09-30T23:59:59+09:00`), 생략 시 제한 없음

기간은 `orderCounts`와 `paymentCounts`에만 적용하며 각각 주문·결제의 생성 시각을 기준으로 양끝을 포함합니다.
`dropCounts`, `stockSummary`, `reconciliationRequired`는 현재 시점의 값이므로 기간의 영향을 받지 않습니다.
`from`이 `to`보다 뒤면 400을 반환합니다.

판매자 종합(`/seller`)은 기간 파라미터를 생략한 현재 요약을 기본으로 사용합니다. 기간을 지정하더라도 재고·DROP 상태·보정 건수에는 적용하지 않으며, 기간별 매출이나 추이 그래프를 이 응답에서 계산하지 않습니다.

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
      "DELIVERED": 6,
      "EXPIRED": 1,
      "CANCELED": 2
    },
    "paymentCounts": {
      "PENDING": 1,
      "SUCCEEDED": 20,
      "FAILED": 3,
      "UNKNOWN": 1,
      "CANCELED": 2
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

- `orderCounts`·`paymentCounts`는 해당 상태의 모든 값을 반환하며, 건수가 없는 상태도 `0`으로 채웁니다.
- `paymentCounts`는 주문이 아니라 결제 시도 건수이므로 재시도가 있으면 `orderCounts` 합계보다 큽니다.
- `dropCounts`는 저장된 상태를 그대로 집계합니다. 상태 전환 배치 주기(약 10초)만큼 늦을 수 있습니다.
- `stockSummary`는 `CANCELED`를 제외한 본인 DROP 전체의 옵션 수량 합계이며, 신규 주문을 받지 않는 옵션(`is_active = false`)도 포함합니다.
  `available`은 `total - reserved - sold - withheld`로 계산한 미할당 수량이며, 종료된 DROP의 잔여 수량이 포함되므로 즉시 구매 가능한 수량과는 다릅니다.
- `reconciliationRequired`는 기간과 무관하게 현재 보정이 끝나지 않은 결제 건수입니다. 0이 아니면 승인은 됐으나 주문이 확정되지 않은 결제가 있다는 뜻입니다.
- `reconciliationRequired`는 일반 `paymentStatus` 주문 필터와 대상이 다릅니다. 전용 목록 조회 계약이 생기기 전까지 건수에서 주문 목록으로 연결하지 않습니다.

### 2.2 DROP별 통계

```http
GET /api/v1/seller/dashboard/drops?status=ENDED&page=0&size=20
```

- **인증**: `SELLER`

**쿼리 파라미터:**
- `status`: `DRAFT`·`WISH`·`GRAB`·`ENDED`·`CANCELED` 중 하나, 생략 시 전체
- `page`: 0부터 시작, 기본값 `0`
- `size`: 1~100, 기본값 `20`

본인 DROP만 최근 생성순(`id` 내림차순)으로 반환합니다.

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
        "saleEndsAt": "2026-09-18T17:00:00+09:00",
        "activeWishCount": 152,
        "availableStock": 4,
        "reservedStock": 2,
        "soldStock": 14,
        "orderCount": 10,
        "soldQuantity": 14,
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

`availableStock`·`reservedStock`·`soldStock`은 DROP에 속한 옵션 수량의 합계이며, 2.1 `stockSummary`와 같은 기준을 씁니다.
신규 주문을 받지 않는 옵션(`is_active = false`)도 포함하고, `availableStock`은 `total - reserved - sold - withheld`로 계산합니다.
이 목록은 `CANCELED` DROP도 반환하지만 2.1 `stockSummary`는 제외하므로, 취소된 DROP이 있으면 목록의 재고 합계가 요약보다 큽니다.

- `saleEndsAt`은 아직 공개하지 않은 `DRAFT`에서는 `null`일 수 있습니다.
- `orderCount`·`salesAmount`는 결제가 확정되고(`paid_at`) 취소되지 않은(`canceled_at`이 없는) 주문만 집계합니다.
- `soldQuantity`는 같은 주문들의 `order_items.quantity` 합계입니다. 재고의 `soldStock`과는 다른 실적 지표이며 주문 건수와도 구분합니다.
- `salesAmount`는 배송비를 포함한 `total_amount`의 누적 합계입니다. 기간 필터와 전체 합계 필드는 제공하지 않으므로 현재 페이지의 합계를 전체 매출로 표시하지 않습니다.
- 미결제·만료·취소 주문을 제외하고 기간 필터도 없으므로, `orderCount`의 합계는 2.1 `orderCounts`의 합계보다 작습니다.

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
