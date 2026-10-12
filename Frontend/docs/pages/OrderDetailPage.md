# 내 주문 상세

- 경로(계획): `/my/orders/:orderId`
- 요구사항: MY-002, PAY-005, CANCEL-001~003, SHIP-003
- [와이어프레임](../wireframes/OrderDetailPage.html)

## 조회·처리 계약

`GET /api/v1/orders/{orderId}`로 본인 주문의 상품 스냅샷, 금액, 주문·최근 결제 상태, 배송 정보를 조회한다. 결제 대기 주문은 [결제 화면](CheckoutPage.md)으로 보낸다. 취소는 `POST /api/v1/orders/{orderId}/cancel`에 필수 사유와 `Idempotency-Key`를 보낸다.

## 화면 구성

- PC: 왼쪽에 상태·상품·배송, 오른쪽에 금액·결제·취소 작업을 배치한다.
- 모바일: 상태→상품→결제/금액→배송→취소 순서로 한 열에 배치한다.
- `PAYMENT_PENDING`, `PAID`, `PREPARING`, `SHIPPED`, `DELIVERED`, `CANCELED`, `EXPIRED` 상태를 구분한다. 배송 전 `shipping`의 null 값은 송장 없음으로 표시한다.

## 행동과 이동

결제 마감 전 `PAYMENT_PENDING`은 결제로 이동한다. 취소 사유 입력과 최종 확인을 거쳐 취소 요청한다. `PAYMENT_PENDING`·`PAID`·`PREPARING`만 취소를 제공하고, 처리 중에는 중복 제출을 막는다. `refundStatus: UNKNOWN`이면 같은 키·본문으로 결과를 확인하며 완료로 표시하지 않는다.

## 상태와 예외

다른 사용자의 주문과 없는 주문은 같은 찾을 수 없음 상태를 보인다. 배송 전에는 택배사·송장번호를 표시하지 않는다. 결제 취소 실패, 주문 상태 충돌, 결과 확인 중을 구분한다. `PAYMENT.md`의 PAY-005는 모든 결제 시도·결과의 기록을 요구하지만 현재 `ORDER.md`의 상세 응답은 최근 `paymentStatus`만 제공한다. 전체 결제 이력 UI는 조회 계약이 추가된 뒤 연결한다.

## 인수 조건

결제 대기·완료·배송 중·배송 완료·취소·만료, 배송 null, 취소 가능/불가, 취소 실패·결과 불명, 직접 진입을 확인한다.

공통: 로딩·빈 결과·실패·권한 오류를 구분하고 키보드 포커스와 직접 진입·뒤로 가기를 확인한다. 기존 색상·글꼴을 유지하며 PC 1440×900, 모바일 390×844에서 가로 넘침이 없어야 한다. 실제 구현은 별도 이슈에서 수행한다.
