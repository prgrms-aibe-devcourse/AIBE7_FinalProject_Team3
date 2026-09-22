# 결제 API 명세

## 1. 결제 API

### 1.1 Mock 결제 요청

```http
POST /api/v1/orders/{orderId}/payments
```

- **인증**: 주문 소유자 (`USER`)
- **헤더**: `Idempotency-Key: {UUID}`

**요청:**

```json
{
  "paymentMethod": "MOCK_CARD",
  "mockResult": "SUCCESS"
}
```

- `mockResult`: `SUCCESS` | `FAILURE` | `TIMEOUT`

**응답:**

```json
{
  "success": true,
  "data": {
    "paymentId": "e7d6c5b4-a392-4817-b6a5-4d3c2b1a0f9e",
    "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
    "orderNumber": "ORD-20260918-000500",
    "amount": 261000,
    "status": "SUCCEEDED",
    "paidAt": "2026-09-18T14:03:00+09:00"
  }
}
```

**검증 항목:**
- 주문번호 일치 여부
- 서버에 저장된 결제 금액과 PG 결과 금액의 일치 여부
- 주문이 `PAYMENT_PENDING` 상태인지 여부
- 결제 유효시간이 지나지 않았는지 여부

### 1.2 Mock PG 결제 결과 수신

```http
POST /api/v1/payments/mock/webhook
```

- **인증**: PG 검증 (Webhook Secret / Signature)

**요청:**

```json
{
  "eventId": "evt-1234",
  "paymentKey": "mock-payment-key",
  "orderNumber": "ORD-20260918-000500",
  "amount": 261000,
  "status": "SUCCEEDED",
  "occurredAt": "2026-09-18T14:03:00+09:00"
}
```

**처리 규칙:**
- `eventId` 또는 `paymentKey`로 중복 처리를 방지한다.
- 결제 금액과 주문번호를 서버 데이터와 비교한다.
- 결제 성공 시 확보 재고를 판매 완료 재고로 확정한다.
- 만료 이후 성공 결과는 주문을 자동 완료하지 않고 결제를 `UNKNOWN`, 보정 상태를 `REQUIRED`로 기록한다.

### 1.3 주문 결제 이력 조회

```http
GET /api/v1/orders/{orderId}/payments
```

- **인증**: 주문 소유자 또는 해당 주문의 판매자

**응답:**

```json
{
  "success": true,
  "data": [
    {
      "paymentId": "e7d6c5b4-a392-4817-b6a5-4d3c2b1a0f9e",
      "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
      "paymentMethod": "MOCK_CARD",
      "amount": 261000,
      "status": "SUCCEEDED",
      "paidAt": "2026-09-18T14:03:00+09:00"
    }
  ]
}
```

---

