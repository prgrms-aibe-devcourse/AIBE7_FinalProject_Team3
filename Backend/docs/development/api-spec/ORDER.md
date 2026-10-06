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
- 구매 가능 여부는 저장 상태가 아니라 서버 시각과 판매 기간으로 판정한다. 저장 상태가 `WISH`여도 시작 전환 배치(GR-18) 이전이면 판매 시작 시각부터 주문할 수 있다.
- DROP 상태가 `WISH` 또는 `GRAB`이어야 한다. `DRAFT`·`CANCELED`·`ENDED`는 시간과 무관하게 거부한다.
- 판매 시작 시각(`now >= saleStartsAt`), 종료 이전(`now < saleEndsAt`)이어야 한다.
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
- `DROP_NOT_ON_SALE` — `DRAFT`·`CANCELED`·`ENDED`이거나 없는 DROP
- `SALE_NOT_STARTED` — 판매 시작 전
- `SALE_ENDED` — 판매 종료 후
- `OPTION_NOT_FOUND`
- `INSUFFICIENT_STOCK`
- `DUPLICATE_IDEMPOTENCY_KEY`

### 1.2 내 주문 목록

```http
GET /api/v1/orders?status=PAID&page=0&size=20
```

- **인증**: 필요 (`USER`)

> 본인 주문만 최신 주문순으로 반환합니다.

**쿼리 파라미터:**
- `status`: 주문 상태 (선택, 예: `PAID`)
- `page`: 페이지 번호 (기본값 `0`)
- `size`: 페이지 크기 (기본값 `20`, 최대 `100`)

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
        "orderNumber": "ORD-20260918-000500",
        "productName": "한정판 스니커즈",
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
    "itemsAmount": 258000,
    "shippingAmount": 3000,
    "totalAmount": 261000,
    "paymentStatus": "SUCCEEDED",
    "paymentExpiresAt": null,
    "shipping": {
      "status": null,
      "carrier": null,
      "trackingNumber": null,
      "deliveredAt": null
    },
    "orderedAt": "2026-09-18T14:00:00+09:00"
  }
}
```

- `paymentExpiresAt`은 `PAYMENT_PENDING` 주문에서만 결제 마감 시각을 담고, 그 외 상태에서는 `null`입니다.
- `paymentStatus`는 최근 결제 시도의 상태이며, 결제 시도가 없으면 `null`입니다.
- `shipping`은 배송 정보가 등록되기 전까지 모든 값이 `null`입니다.
- `shipping.deliveredAt`은 Mock 배송 완료 결과가 반영된 시각(`shipments.delivered_at`)이며, 배송 완료 전에는 `null`입니다.

**오류 코드:**
- `ORDER_NOT_FOUND`: 주문이 없거나 다른 사용자의 주문인 경우. 주문 존재 여부를 노출하지 않도록 두 경우를 구분하지 않습니다.
- `RESOURCE_NOT_FOUND`: 주문 ID 형식이 올바르지 않은 경우

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

- `reason`: 취소 사유 (필수, 공백 불가, 최대 500자)

**응답:**

```json
{
  "success": true,
  "data": {
    "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
    "status": "CANCELED",
    "refundStatus": "SUCCEEDED",
    "canceledAt": "2026-09-18T14:10:00+09:00"
  }
}
```

- 취소가 끝났거나 결제 취소 결과를 확인 중이면 `200 OK`로 응답하고 `status`, `refundStatus`로 구분합니다.

| 상황 | `status` | `refundStatus` | `canceledAt` |
| --- | --- | --- | --- |
| 결제 전 주문 취소 | `CANCELED` | `NONE` | 취소 시각 |
| 결제 후 주문 취소, 결제 취소 성공 | `CANCELED` | `SUCCEEDED` | 취소 시각 |
| 결제 후 주문 취소, 결제 취소 결과 확인 중 | `PAID` 또는 `PREPARING` (변경 없음) | `UNKNOWN` | `null` |

- `refundStatus`가 `UNKNOWN`이면 클라이언트는 같은 `Idempotency-Key`와 같은 본문으로 재전송해 결과를 확인합니다. 취소 확인 중에는 판매자가 배송 준비·발송 처리를 할 수 없습니다.

**처리 규칙:**
- 본인 주문만 취소할 수 있다. 다른 사용자의 주문은 존재 여부를 숨기고 `ORDER_NOT_FOUND`로 응답한다(1.3과 같음).
- 취소 가능 상태는 `PAYMENT_PENDING`, `PAID`, `PREPARING`이다. 배송이 시작된 `SHIPPED`·`DELIVERED`와 이미 끝난 `CANCELED`·`EXPIRED`는 `ORDER_NOT_CANCELABLE`로 거부한다. 시간 제한은 없다.
- `PAYMENT_PENDING` 주문은 PG 호출 없이 즉시 취소하고 확보 재고를 반환한다.
  - 결제 승인 응답을 기다리는 결제(`PENDING`)가 있으면 `ORDER_STATUS_CONFLICT`로 거부한다. 잠시 뒤 다시 요청한다.
  - 결과 불명 결제(`UNKNOWN`)가 있어도 취소한다. 이후 승인이 확인되면 보정 대상으로 기록하고 환불한다.
- `PAID`, `PREPARING` 주문은 토스페이먼츠 결제 취소(전액)에 성공한 뒤 `CANCELED`로 전환하고 판매 수량을 반환한다. 결제 취소와 재고 반환은 같은 트랜잭션에서 반영한다.
  - 토스가 취소를 거절하면 주문은 바뀌지 않고 `PAYMENT_CANCEL_FAILED`로 응답한다. 새 `Idempotency-Key`로 다시 요청할 수 있다.
  - 결제 취소 결과를 알 수 없으면 주문을 바꾸지 않고 `refundStatus: UNKNOWN`으로 응답한다.
- 반환한 재고는 가용 재고로 돌린다. 재고 반환은 한 번만 수행한다.
- 처리 순서와 트랜잭션 경계는 [ERD.md](../ERD.md) 3.3을 따른다.

**멱등성:**
- 같은 `Idempotency-Key`와 같은 본문이 다시 오면 최초 취소 결과를 반환한다. 결제 취소 결과를 확인 중(`UNKNOWN`)이면 같은 서버 멱등 키로 토스 결제 취소를 다시 요청해 확정한 뒤 응답한다.
- 같은 `Idempotency-Key`에 다른 본문이 오면 `DUPLICATE_IDEMPOTENCY_KEY`로 거부한다.
- 결제 취소를 진행·확인 중인 주문에 다른 `Idempotency-Key`로 요청하면 `ORDER_STATUS_CONFLICT`로 거부한다.
- 취소 요청 키와 요청 해시는 `orders.cancel_idempotency_key`, `orders.cancel_request_hash`에 저장하며 키 범위는 주문별이다. 토스 결제 취소 요청에는 서버가 만든 UUID(`payment_cancellations.idempotency_key`)를 쓴다.

**오류 코드:**

| 오류 코드 | HTTP 상태 | 조건 |
| --- | --- | --- |
| `INVALID_IDEMPOTENCY_KEY` | 400 | `Idempotency-Key` 헤더가 없거나 UUID 형식이 아님 |
| `VALIDATION_FAILED` | 400 | `reason` 누락·공백 또는 500자 초과 |
| `RESOURCE_NOT_FOUND` | 404 | 경로의 주문 ID 형식이 올바르지 않음 |
| `ORDER_NOT_FOUND` | 404 | 주문이 없거나 다른 사용자의 주문 |
| `DUPLICATE_IDEMPOTENCY_KEY` | 409 | 같은 멱등 키에 다른 요청 본문 |
| `ORDER_NOT_CANCELABLE` | 409 | 취소할 수 없는 상태(`SHIPPED`, `DELIVERED`, `CANCELED`, `EXPIRED`) |
| `ORDER_STATUS_CONFLICT` | 409 | 결제 승인 응답 대기 중, 또는 다른 취소 요청을 처리·확인 중 |
| `PAYMENT_CANCEL_FAILED` | 502 | 토스페이먼츠가 결제 취소를 거절함 |

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

- `items`, `shipping`은 1.3 내 주문 상세와 같은 형식이며, `shipping.deliveredAt`도 함께 반환합니다.

### 2.3 배송 준비 처리

```http
POST /api/v1/seller/orders/{orderId}/prepare-shipment
```

- **인증**: `SELLER`

**상태 전이:**
- `PAID` → `PREPARING`

**처리 조건:**
- 해당 주문의 DROP 소유자인 판매자만 요청할 수 있다.
- 소비자 취소로 결제 취소를 진행·확인 중인(`payment_cancellations.status`가 `REQUESTED`·`UNKNOWN`) 주문은 배송 준비로 전환할 수 없다(`PAYMENT_CANCELLATION_UNKNOWN`).
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
- 소비자 취소로 결제 취소를 진행·확인 중인 주문은 발송 처리할 수 없다(`PAYMENT_CANCELLATION_UNKNOWN`).

### 2.5 송장 정보 수정

```http
PATCH /api/v1/seller/orders/{orderId}/shipment
```

- **인증**: `SELLER`

**요청:**

```json
{
  "carrier": "CJ대한통운",
  "trackingNumber": "482910355174"
}
```

> 판매자가 송장번호를 직접 입력하므로 오기가 발생할 수 있습니다. 잘못 등록된 송장은 소비자 배송 조회를 계속 실패시키고 2.4는 이미 등록된 주문을 거부하므로, 정정 경로를 별도로 둡니다.

**처리 규칙:**
- 해당 주문의 DROP 소유자인 판매자만 요청할 수 있다.
- `SHIPPED` 주문만 수정한다. `DELIVERED` 주문은 배송이 끝나 조회할 이유가 없으므로 거부한다.
- `carrier`와 `trackingNumber`의 검증은 2.4와 같다.
- 상태 전이가 없으므로 `Idempotency-Key`를 요구하지 않는다. 같은 값으로 반복 요청해도 결과가 같다.
- `shipments`의 `carrier_code`와 `tracking_number`만 갱신한다. `shipped_at`, `idempotency_key`, `request_hash`는 최초 등록 값을 유지한다.
- 주문 행을 잠그고 상태를 확인해 상태 변경과 경합해도 한 쪽만 반영된다.

**응답:**

```json
{
  "success": true,
  "data": {
    "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
    "status": "SHIPPED",
    "carrier": "CJ대한통운",
    "trackingNumber": "482910355174"
  }
}
```

**오류 코드:**
- `ORDER_NOT_FOUND`: 주문이 없는 경우
- `ORDER_ACCESS_DENIED`: 본인 DROP의 주문이 아닌 경우
- `ORDER_STATUS_CONFLICT`: `SHIPPED`가 아닌 주문이거나 배송 정보가 없는 경우

### 2.6 Mock 배송 완료 반영

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

