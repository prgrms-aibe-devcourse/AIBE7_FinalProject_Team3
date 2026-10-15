# 재고 API 명세

## 1. 재고 조회 API

### 1.1 SSE 변경 알림 (명세 정의·미구현)

GR-109에서 재고 스냅샷 전송 대신 상품 변경 알림 후 기존 조회 API를 재사용하는 방식을 채택했다. 상품 상세와 WISH·GRAB 목록에 적용하며 판매 시작·종료·취소 알림도 포함한다. 확정 정책과 후속 구현·검증 기준은 [실시간 변경 전달 계약](../REALTIME_STOCK.md)을 따른다.

기존 단일 상품 경로와 수량 포함 이벤트 예시는 대체한다. 구독은 `GET /api/v1/drops/stock-stream?dropIds=100,101`이며, 쿼리·공개 정책·이벤트·오류·복구는 정본 6~9절을 따른다. 초기 운영 기본값·검증 목표는 정본 11~13절을 따르며 성능 미실측 상태다. 현재 구현된 endpoint나 OpenAPI 생성 타입으로 간주하지 않는다.

아래 재고 조회의 요청·응답 형식과 공개 정책은 유지한다. SSE 알림 후 재조회와 유실 복구에 사용하며, 실제 주문 가능 여부는 주문 API가 서버의 최신 재고를 기준으로 다시 판단한다.

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

