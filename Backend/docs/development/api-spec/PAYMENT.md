# 결제 API 명세

## 1. 결제 API

> 결제 PG는 토스페이먼츠 **테스트 환경**(테스트 API 키, 결제창 방식)을 사용합니다. 실제 결제는 일어나지 않으며, 이 문서의 "Mock 결제"는 토스페이먼츠 테스트 결제를 뜻합니다. 선정 근거는 [TECHSTACK.md](../TECHSTACK.md)를 따릅니다.

### 1.0 결제 흐름

```text
① 프론트: 주문 상세의 orderNumber·totalAmount로 토스페이먼츠 결제창 호출 (클라이언트 키)
② 사용자: 결제창에서 결제 인증
③ 토스:   successUrl로 paymentKey, orderId(=orderNumber), amount 전달
④ 프론트: POST /api/v1/orders/{orderId}/payments 로 paymentKey, amount 전달
⑤ 서버:   검증 → 결제 시도 PENDING 저장 → 토스 결제 승인 API 호출(트랜잭션 밖) → 결과 반영
```

- 토스페이먼츠에 보내는 `orderId`는 서버 주문의 `orderNumber`입니다. 경로의 `{orderId}`(주문 UUID)와 다릅니다.
- 토스는 결제 인증 후 10분 안에 승인하지 않은 결제를 만료시키므로, 프론트는 successUrl 도착 즉시 ④를 호출합니다.

### 1.1 Mock 결제 요청 (토스페이먼츠 결제 승인)

```http
POST /api/v1/orders/{orderId}/payments
```

- **인증**: 주문 소유자 (`USER`)
- **헤더**: `Idempotency-Key: {UUID}` (필수)

**요청:**

```json
{
  "paymentKey": "tgen_20260918140300abcd1",
  "amount": 261000
}
```

- `paymentKey`: 결제창 successUrl로 받은 값 (필수, 최대 200자)
- `amount`: successUrl로 받은 결제 금액 (필수, 1 이상)

**응답:** `200 OK`

```json
{
  "success": true,
  "data": {
    "paymentId": "e7d6c5b4-a392-4817-b6a5-4d3c2b1a0f9e",
    "orderId": "b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d",
    "orderNumber": "ORD-20260918-000500",
    "amount": 261000,
    "status": "SUCCEEDED",
    "reconciliationStatus": "NONE",
    "paidAt": "2026-09-18T14:03:00+09:00",
    "failure": null
  }
}
```

- 결제 시도가 만들어진 뒤의 결과(`SUCCEEDED`, `FAILED`, `UNKNOWN`)는 모두 `200 OK`로 응답하고 `status`로 구분합니다.
- `paidAt`은 `SUCCEEDED`일 때만 값이 있습니다.
- `failure`는 `FAILED`일 때 `{ "code": "REJECT_CARD_PAYMENT", "message": "..." }` 형태로 PG 실패 코드와 민감정보를 제거한 메시지를 담고, 그 외에는 `null`입니다.

**결과별 처리:**

| 토스 승인 결과 | 결제 | 주문·예약·재고 |
| --- | --- | --- |
| 승인 성공 (`DONE`) | `SUCCEEDED` | 주문 `PAID`, 예약 `HELD → COMMITTED`, `reserved_quantity` 감소, `sold_quantity` 증가 |
| 승인 거절 (토스 오류 응답) | `FAILED`, 실패 코드·메시지 기록 | 변경 없음. 주문은 결제 마감 전까지 `PAYMENT_PENDING` 유지 |
| 결과 불명 (타임아웃·응답 유실·토스 5xx) | 아래 즉시 조회 후 결정 | 조회로 확정되기 전에는 변경 없음 |
| 승인 성공이지만 반영 시점에 결제 마감이 지남 | `SUCCEEDED`, 보정 `REQUIRED` | 주문을 자동 완료하지 않음 |
| 승인 성공이지만 옵션의 `reserved_quantity`가 예약 수량보다 적음 | `SUCCEEDED`, 보정 `REQUIRED` | 변경 없음. 재고 원장 불일치로 보고 주문을 확정하지 않음 |

**결과 불명 시 즉시 조회:**
- 승인 결과가 불명이면 `paymentKey`로 토스 결제 조회 API를 한 번 호출합니다.
  - 조회 결과 `DONE`: 승인 성공과 같이 반영합니다.
  - 조회 결과 미승인(`DONE`이 아님): 결제를 `FAILED`로 기록해 재시도를 허용합니다.
  - 조회도 실패: 결제를 `UNKNOWN`, 보정 `REQUIRED`로 기록하고 주문·재고를 확정하지 않습니다.

**검증 항목 (토스 호출 전):**
- 주문 소유자 여부 (다른 사용자의 주문은 `ORDER_NOT_FOUND`)
- 주문이 `PAYMENT_PENDING` 상태인지 여부
- 결제 유효시간(`payment_expires_at`)이 지나지 않았는지 여부
- 요청 `amount`와 서버에 저장된 주문 `total_amount`의 일치 여부
- 같은 주문에 진행 중인 결제(`PENDING`, `UNKNOWN`)가 없는지 여부
- 같은 `paymentKey`로 이미 처리된 결제가 없는지 여부

**검증 항목 (토스 응답 수신 후):**
- 응답 `orderId`가 주문의 `orderNumber`와 일치하는지 여부
- 응답 `totalAmount`가 주문 `total_amount`와 일치하는지 여부. 불일치하면 주문을 확정하지 않고 보정 `REQUIRED`로 기록합니다.

**멱등성:**
- 같은 `Idempotency-Key`와 같은 요청 본문이 다시 오면 새 결제 시도를 만들지 않고 최초 결제 시도의 현재 결과를 반환합니다.
  - 최초 요청이 아직 토스 승인을 기다리는 중이면 `status`는 `PENDING`입니다. 잠시 후 같은 요청을 다시 보내면 확정된 결과를 받습니다.
- 같은 `Idempotency-Key`에 다른 요청 본문이 오면 `409 DUPLICATE_IDEMPOTENCY_KEY`로 거부합니다.
- 클라이언트 `Idempotency-Key`는 `payments.client_idempotency_key`에 저장하고, 토스 승인 요청의 `Idempotency-Key` 헤더에는 서버가 새로 만든 UUID(`payments.idempotency_key`)를 사용합니다.

**재시도 정책:**
- 결제 시도는 결제 마감 전까지 횟수 제한 없이 허용합니다. 결제창을 다시 열 때마다 새 `paymentKey`가 발급됩니다.
- 같은 주문에 진행 중인 결제(`PENDING`, `UNKNOWN`)가 있으면 이중 결제를 막기 위해 새 결제 요청을 거부합니다.
- 진행 중인 결제가 `UNKNOWN`이거나 서버 장애 등으로 60초 넘게 `PENDING`에 머물러 있으면, 새 요청을 판단하기 전에 해당 결제의 `paymentKey`로 토스 결제 조회 API를 호출해 상태를 먼저 정리합니다.
  - 조회 결과가 인증만 끝나고 승인 요청을 받지 않은 상태(`IN_PROGRESS`)면, 최초 승인 요청과 같은 서버 `Idempotency-Key`로 승인을 다시 요청합니다. 최초 요청이 토스에 닿았다면 토스가 같은 키의 최초 결과를 돌려주므로 중복 승인되지 않습니다.
  - 이전 결제가 이 정리로 승인되면 주문은 `PAID`가 되고, 새 요청은 `409 PAYMENT_ALREADY_PROCESSED`로 거부합니다(새 `paymentKey`는 승인하지 않습니다).

**오류 코드:**

| 오류 코드 | HTTP 상태 | 조건 |
| --- | --- | --- |
| `INVALID_IDEMPOTENCY_KEY` | 400 | `Idempotency-Key` 헤더가 없거나 UUID 형식이 아님 |
| `VALIDATION_FAILED` | 400 | `paymentKey`·`amount` 누락 또는 형식 오류 |
| `RESOURCE_NOT_FOUND` | 404 | 경로의 주문 ID 형식이 올바르지 않음 |
| `ORDER_NOT_FOUND` | 404 | 주문이 없거나 다른 사용자의 주문 |
| `DUPLICATE_IDEMPOTENCY_KEY` | 409 | 같은 멱등 키에 다른 요청 본문 |
| `PAYMENT_ALREADY_PROCESSED` | 409 | 이미 결제 완료된 주문, 진행 중인 결제가 있는 주문, 이미 처리된 `paymentKey` |
| `INVALID_STATE_TRANSITION` | 409 | 취소된 주문 |
| `PAYMENT_AMOUNT_MISMATCH` | 422 | 요청 `amount`가 주문 금액과 다름 |
| `PAYMENT_EXPIRED` | 422 | 결제 유효시간이 지났거나 만료된 주문 |

### 1.2 토스페이먼츠 결제 결과 수신 (웹훅)

> GR-48 범위 밖입니다. 외부에서 접근 가능한 URL이 필요하므로 후속 작업에서 구현합니다.

```http
POST /api/v1/payments/toss/webhook
```

- **인증**: 없음. 웹훅 본문을 그대로 믿지 않고 토스 결제 조회 API로 상태를 다시 확인합니다.

**처리 규칙:**
- `paymentKey`와 이벤트 식별 값으로 중복 처리를 방지한다 (`payment_events.event_key`).
- 결제 금액과 주문번호를 서버 데이터와 비교한다.
- 결제 성공 시 확보 재고를 판매 완료 재고로 확정한다.
- 만료 이후 성공 결과는 주문을 자동 완료하지 않고 보정 상태를 `REQUIRED`로 기록한다.

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
      "provider": "TOSS",
      "amount": 261000,
      "status": "SUCCEEDED",
      "paidAt": "2026-09-18T14:03:00+09:00"
    }
  ]
}
```

- `provider`: 결제 PG (`payments.provider`). 결제수단(카드·간편결제 등)은 저장하지 않으므로 응답에 포함하지 않습니다.

---

