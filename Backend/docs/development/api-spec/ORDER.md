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
    "shippingAmount": 3000,
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
> 판매자가 송장 정보를 등록하기 전에는 Shipment 행이 없으므로 `shipping`의 필드는 `null`입니다. 송장 등록과 발송 처리 후에는 `status`가 `SHIPPED`가 되고 배송 정보가 채워집니다. Mock 배송 완료 결과가 반영되면 `status`는 `DELIVERED`가 됩니다.

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

**처리 조건:**
- 해당 주문의 DROP 소유자인 판매자만 요청할 수 있다.
- 결제 취소 상태가 `UNKNOWN`인 주문은 배송 준비로 전환할 수 없다.
- 주문 행 잠금으로 취소와 경합해도 하나의 상태 전이만 반영한다.

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
  "trackingNumber": "123456789012"
}
```

**상태 전이:**
- `PREPARING` → `SHIPPED`

**처리 규칙:**
- `carrier`와 `trackingNumber`는 공백이 아닌 문자열이며 각각 최대 50자·100자다. 숫자 전용 송장 형식은 강제하지 않는다.
- 요청 키 범위는 주문별이다. 같은 주문·키·본문 재요청은 최초 성공 결과를 반환하고, 같은 키에 다른 본문이면 `409 DUPLICATE_IDEMPOTENCY_KEY`를 반환한다.
- 주문 행을 잠근 트랜잭션에서 소유권·`PREPARING` 상태를 확인하고 배송 정보와 상태를 함께 저장한다. `shipped_at`은 이 요청을 처리한 서버 시각으로 기록한다.
- 발송 상태는 주문의 `SHIPPED` 상태와 일치한다. 취소와 동시에 요청되면 먼저 커밋한 상태 전이만 성공한다.

### 2.5 Mock 배송 완료 반영

```http
POST /api/v1/mock/orders/{orderId}/delivery/complete
```

- **사용 범위**: 로컬·테스트 환경 전용. 운영 환경에서 비활성화한다.
- **요청 본문**: 없음

Mock 배송 시스템이 배송 완료 결과를 전달하면 주문을 자동 완료 처리한다.

**처리 조건:**
- 주문 행 잠금으로 취소 등 다른 상태 변경과 경합해도 하나의 상태 전이만 반영한다.
- `SHIPPED` 주문만 `DELIVERED`로 전환하고 `shipments.delivered_at`에 서버 시각을 기록한다.
- 이미 `DELIVERED`인 주문에 반복 전달되면 현재 완료 결과를 반환한다.

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

