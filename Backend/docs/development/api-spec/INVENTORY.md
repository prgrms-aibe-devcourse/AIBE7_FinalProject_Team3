# 재고 API 명세

## 1. 재고 조회 API

### 1.1 SSE 재고 구독 (MVP 이후)

> MVP에서는 아래 SSE 엔드포인트를 구현하지 않습니다. 재고 재조회만으로 시작하고 실제 필요성과 부하를 확인한 뒤 도입합니다.

```http
GET /api/v1/drops/{dropId}/stock-stream
Accept: text/event-stream
```

- **인증**: 불필요

**이벤트 예시:**

```http
event: stock-changed
id: 10001
data: {"dropId":100,"optionId":1001,"availableStock":8,"soldOut":false,"occurredAt":"2026-09-20T10:01:00+09:00"}
```

**전체 품절 이벤트:**

```http
event: drop-sold-out
id: 10002
data: {"dropId":100,"soldOut":true,"occurredAt":"2026-09-20T10:05:00+09:00"}
```

> 도입 이후에도 실시간 데이터는 사용자 표시용이며 실제 주문 가능 여부는 주문 API가 서버의 최신 재고를 기준으로 다시 판단합니다.

### 1.2 현재 재고 재조회

```http
GET /api/v1/drops/{dropId}/stocks
```

- **인증**: 불필요
- **정책**: 공개 상세와 동일하게 `isPublic()` 기준을 적용한다. DRAFT는 `DROP_NOT_FOUND`(404)이며 WISH·GRAB·ENDED·CANCELED는 200이다. CANCELED여도 가용 재고를 0으로 바꾸지 않는다.
- 활성 SKU만 반환하고 SKU `sortOrder` 순서로 정렬한다. 반환할 SKU가 없으면 `options: []`다.
- `soldOut = availableStock == 0`이며, 재고 여부만 나타낸다. 판매 기간 종료나 CANCELED를 이유로 품절 처리하지 않는다.
- 응답 이후 주문으로 재고가 바뀔 수 있으며, 실제 주문 가능 여부는 주문 API가 서버의 최신 재고로 다시 판단한다.

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "options": [
      {
        "optionId": 1001,
        "availableStock": 8,
        "soldOut": false
      }
    ],
    "serverTime": "2026-09-20T10:01:00+09:00"
  }
}
```

---

