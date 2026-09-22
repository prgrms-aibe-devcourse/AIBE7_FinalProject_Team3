# 주문 및 배송 API 명세

## 1. 주문 API

### 1.1 주문 생성 및 재고 확보

```http
POST /api/v1/orders
```

- **인증**: 필요 (`USER`)
- **헤더**: `Idempotency-Key: {UUID}`

**요청:**

```json
{
  "dropId": 100,
  "items": [
    {
      "optionId": 1001,
      "quantity": 2
    }
  ],
  "shippingAddress": {
    "recipient": "홍길동",
    "phone": "01012345678",
    "postalCode": "06236",
    "address1": "서울특별시 강남구 테헤란로 1",
    "address2": "101호"
  }
}
```

> 클라이언트는 가격이나 총결제금액을 전달하지 않습니다.

**처리 조건:**
- 서버 시각 기준 DROP 상태가 `GRAB`이어야 한다.
- 판매 시작 이후, 종료 이전이어야 한다.
- 요청한 모든 옵션에 충분한 가용 재고가 있어야 한다.
- 재고 확보와 주문 생성은 하나의 트랜잭션으로 처리한다.

**응답:** `201 Created`

```json
{
  "success": true,
  "data": {
    "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
    "orderNumber": "ORD-20260918-000500",
    "status": "PAYMENT_PENDING",
    "items": [
      {
        "optionId": 1001,
        "productName": "한정판 스니커즈",
        "optionName": "코튼 / 롱",
        "unitPrice": 129000,
        "quantity": 2,
        "subtotal": 258000
      }
    ],
    "itemsAmount": 258000,
    "shippingFee": 3000,
    "totalAmount": 261000,
    "paymentExpiresAt": "2026-09-18T14:10:00+09:00"
  }
}
```

**오류 코드:**
- `DROP_NOT_ON_SALE`
- `SALE_NOT_STARTED`
- `SALE_ENDED`
- `OPTION_NOT_FOUND`
- `INSUFFICIENT_STOCK`
- `DUPLICATE_IDEMPOTENCY_KEY`

### 1.2 내 주문 목록

```http
GET /api/v1/orders?status=PAID&page=0&size=20
```

- **인증**: 필요 (`USER`)

> 본인 주문만 반환합니다.

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
        "orderNumber": "ORD-20260918-000500",
        "status": "PAID",
        "totalAmount": 261000,
        "orderedAt": "2026-09-18T14:00:00+09:00"
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

### 1.3 내 주문 상세

```http
GET /api/v1/orders/{orderId}
```

- **인증**: 필요 (`USER`)

> 응답에는 주문 당시 스냅샷 정보를 반환합니다.

**응답:**

```json
{
  "success": true,
  "data": {
    "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
    "orderNumber": "ORD-20260918-000500",
    "status": "PAID",
    "items": [
      {
        "productName": "한정판 스니커즈",
        "optionName": "코튼 / 롱",
        "unitPrice": 129000,
        "quantity": 2,
        "subtotal": 258000
      }
    ],
    "totalAmount": 261000,
    "paymentStatus": "SUCCEEDED",
    "shipping": {
      "status": null,
      "carrier": null,
      "trackingNumber": null
    },
    "orderedAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 1.4 주문 취소

```http
POST /api/v1/orders/{orderId}/cancel
```

- **인증**: 필요 (`USER`)
- **헤더**: `Idempotency-Key: {UUID}`

**요청:**

```json
{
  "reason": "단순 변심"
}
```

**처리 규칙:**
- 본인 주문만 취소할 수 있다.
- `PAYMENT_PENDING` 주문은 즉시 취소하고 확보 재고를 반환한다.
- `PAID` 주문은 PG 결제 취소 성공 후 `CANCELED`로 전환한다.
- 배송이 시작된 주문은 취소할 수 없다.
- 재고 반환은 한 번만 수행한다.

**오류 코드:**
- `ORDER_NOT_FOUND`
- `ORDER_ACCESS_DENIED`
- `ORDER_NOT_CANCELABLE`
- `PAYMENT_CANCEL_FAILED`
- `ORDER_STATUS_CONFLICT`

---

## 2. 판매자 주문 및 배송 API

### 2.1 판매자 주문 목록 조회

```http
GET /api/v1/seller/orders
```

- **인증**: `SELLER`

**쿼리 파라미터:**
- `dropId`: DROP ID (예: `100`)
- `orderStatus`: `PAID` 등
- `paymentStatus`: `SUCCEEDED` 등
- `page`: 페이지 번호
- `size`: 페이지 크기

> 자신이 등록한 DROP의 주문만 반환합니다.

### 2.2 판매자 주문 상세 조회

```http
GET /api/v1/seller/orders/{orderId}
```

- **인증**: `SELLER`

> 해당 주문에 포함된 DROP의 소유권을 검증합니다.

### 2.3 배송 준비 처리

```http
POST /api/v1/seller/orders/{orderId}/prepare-shipment
```

- **인증**: `SELLER`

**상태 전이:**
- `PAID` → `PREPARING`

**응답:**

```json
{
  "success": true,
  "data": {
    "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
    "status": "PREPARING"
  }
}
```

### 2.4 배송 정보 등록 및 발송 처리

```http
POST /api/v1/seller/orders/{orderId}/shipment
```

- **인증**: `SELLER`
- **헤더**: `Idempotency-Key: {UUID}`

**요청:**

```json
{
  "carrier": "CJ대한통운",
  "trackingNumber": "123456789012",
  "shippedAt": "2026-09-19T10:00:00+09:00"
}
```

**상태 전이:**
- `PREPARING` → `SHIPPED`

> 취소 처리와 동시에 요청된 경우 하나의 상태 전이만 성공해야 합니다.

### 2.5 배송 완료 처리

```http
POST /api/v1/seller/orders/{orderId}/delivery-complete
```

- **인증**: `SELLER`

**상태 전이:**
- `SHIPPED` → `DELIVERED`

**응답:**

```json
{
  "success": true,
  "data": {
    "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
    "status": "DELIVERED"
  }
}
```

---

