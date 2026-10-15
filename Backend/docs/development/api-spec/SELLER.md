# 판매자 API 명세

## 1. 판매자 API

### 1.1 판매자 신청 서류 업로드

```http
POST /api/v1/seller-documents
```

- **인증**: `USER`
- **요청**: `multipart/form-data` — `documentType`(폼 필드) + `file`(파일 1개)
- `documentType`: `BUSINESS_LICENSE`(사업자등록증 사본) / `ID_CARD`(신분증 사본) / `BANKBOOK`(통장 사본) / `MAIL_ORDER_BUSINESS_REPORT`(통신판매업신고증) 중 하나
- 서버가 파일을 받아 랜덤 DEK로 AES-256-GCM 암호화한 뒤 원본은 버리고 비공개 버킷(`seller-documents`)에 암호문만 저장한다. 공개 `imageUrl`은 내려주지 않고 `documentId`(UUID)만 발급한다.
- `ID_CARD`는 주민등록번호 뒷자리를 가려서 촬영한 이미지를 제출해야 한다. 가려지지 않은 경우 운영자가 심사 단계(1.6절)에서 확인 후 반려한다.
- 암호화 전에 이미지로 디코딩되는 파일인지 검증한다. 디코딩할 수 없으면(이미지가 아니거나 손상된 파일) `400 VALIDATION_FAILED`로 거부한다. 운영자 뷰어(1.7절)는 저장 시점에 이미 검증된 파일만 복호화·열람하므로 디코딩 실패를 다시 겪지 않는다.

**응답:** `201 Created`

```json
{
  "success": true,
  "data": {
    "documentId": "b1f2c3d4-5678-4abc-9012-3456789abcde",
    "documentType": "ID_CARD"
  }
}
```

### 1.2 판매자 등록 신청

```http
POST /api/v1/seller-applications
```

- **인증**: `USER`

**요청:**

```json
{
  "brandName": "길동상점",
  "contactEmail": "seller@example.com",
  "businessRegistrationNumber": "1234567890",
  "description": "한정판 상품 판매점",
  "documentIds": {
    "BUSINESS_LICENSE": "b1f2c3d4-5678-4abc-9012-3456789abcde",
    "ID_CARD": "c2f3d4e5-6789-4abc-9012-3456789abcde",
    "BANKBOOK": "d3f4e5f6-7890-4abc-9012-3456789abcde",
    "MAIL_ORDER_BUSINESS_REPORT": "e4f5f6a7-8901-4abc-9012-3456789abcde"
  }
}
```

- `businessRegistrationNumber`: 하이픈 없는 10자리 숫자. 국세청 사업자등록 상태조회로 검증하며, 계속사업자가 아니면(휴업자·폐업자) `422 BUSINESS_NOT_ACTIVE`로 거부한다. 조회 실패·타임아웃이면 검증을 생략하고 `PENDING`으로 접수한다.
- 이미 `PENDING` 또는 `APPROVED` 상태의 신청이 있으면 `409 SELLER_APPLICATION_ALREADY_EXISTS`로 거부한다. `REJECTED` 상태에서 다시 호출하면 기존 레코드를 갱신해 재신청으로 처리한다(신청 시각·심사 정보 초기화).
- 다른 계정이 같은 `businessRegistrationNumber`로 이미 신청했으면 `409 DUPLICATE_BUSINESS_REGISTRATION_NUMBER`로 거부한다.
- `documentIds`는 1.1절에서 발급받은 `documentId`를 4종 모두 채워야 한다. 하나라도 비어 있으면 `400 VALIDATION_FAILED`로 거부한다.

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

### 1.3 내 판매자 신청 조회

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
    "businessRegistrationNumber": "1234567890",
    "description": "한정판 상품 판매점",
    "status": "PENDING",
    "appliedAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 1.4 판매자 신청 목록 조회

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
        "businessRegistrationNumber": "1234567890",
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

### 1.5 판매자 신청 승인

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

### 1.6 판매자 신청 반려

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

### 1.7 판매자 신청 서류 열람 (전용 뷰어)

```http
GET /api/v1/admin/seller-applications/{applicationId}/documents/{documentType}/view
```

- **인증**: `ADMIN`
- `documentType`: `BUSINESS_LICENSE` / `ID_CARD` / `BANKBOOK` / `MAIL_ORDER_BUSINESS_REPORT`
- presigned URL을 발급하지 않는다. 서버가 암호문을 복호화하고 그 위에 운영자 ID + 열람 시각 워터마크를 합성해 이미지 바이너리(`image/*`)를 직접 스트리밍한다.
- 응답에 `Cache-Control: no-store`를 포함해 브라우저·프록시 캐시를 막는다.
- 요청마다 `seller_document_access_logs`에 열람 기록(운영자 ID, 신청 ID, 서류 종류, 열람 시각)을 남긴다.
- 다운로드 자체를 막지는 않는다(스트림을 저장해도 워터마크가 찍힌 사본만 남는다). 화면 캡처 방지는 프론트 담당이다.
- 신청이 없거나 해당 `documentType`의 서류가 없으면 `404 SELLER_APPLICATION_NOT_FOUND`로 거부한다.

**응답:** `200 OK`, `Content-Type: image/png`(또는 원본 포맷) 바이너리 스트림

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
- `dropId`: 본인 DROP ID, 생략 시 전체. 지정하면 해당 상품 한 건만 필터링합니다.
- `page`: 0부터 시작, 기본값 `0`
- `size`: 1~100, 기본값 `20`

본인 DROP만 최근 생성순(`id` 내림차순)으로 반환합니다.
`status`와 `dropId`를 함께 지정하면 두 조건을 모두 만족해야 합니다. 판매 실적 화면은 DROP 상세에서 받은 `dropId`를 URL에 보존해 해당 상품으로 직접 진입할 수 있습니다.

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
