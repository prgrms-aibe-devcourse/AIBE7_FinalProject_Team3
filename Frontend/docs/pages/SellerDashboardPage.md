# 판매자 종합

- 경로: `/seller` (`HashRouter`, 실제 URL은 `/#...`)
- 구현 이슈: GR-82 · GR-41 · GR-38
- [와이어프레임](../wireframes/SellerDashboardPage.html)

## 조회 계약

`GET /api/v1/seller/dashboard/summary`와 START/END 각각의 `upcoming-drops`를 사용한다. `dropCounts`, `stockSummary`, `reconciliationRequired`는 현재값이고 `from/to`는 `orderCounts`, `paymentCounts`에만 적용한다.

## 화면 구성

- PC: 종합 제목과 새 DROP 버튼, 현재 DROP 상태·재고·처리할 주문의 요약 카드, 시작/종료 임박 일부, 기간 주문/결제 건수 순서로 배치한다. 긴 DROP 관리 목록과 판매 완료 목록은 두지 않는다.
- 모바일: 요약 카드를 세로 또는 2열로 읽기 쉽게 배치하고 임박 목록·기간 건수를 순서대로 쌓는다. 넓은 표를 복제하지 않는다.

## 행동과 이동

DROP 상태 카드는 `/seller/drops?status=...`, 주문 상태 카드는 `/seller/orders?orderStatus=...`, 임박 상품은 `/seller/drops/:dropId`로 이동한다. 보정 건수는 전용 대상 목록 계약 전까지 링크 없이 안내한다. 기간 선택은 주문·결제 건수 영역 안에 둔다.

## 상태와 예외

요약과 START/END 임박 조회는 부분 실패를 분리해 성공한 영역을 유지한다. 0건은 숫자 0 또는 빈 안내로 표시한다. 401/403은 판매자 권한 흐름을 따른다.

## 인수 조건

기간을 바꿔도 현재 DROP/재고/보정 수치가 기간 집계처럼 보이지 않는다. 주문 수와 결제 시도 수를 구분하고 카드 링크가 정확한 필터로 이어진다.

공통: 로딩·빈 결과·실패·권한 오류를 구분하고 키보드 포커스와 직접 진입·뒤로 가기를 확인한다. 기존 색상·글꼴·이미지 비율을 유지하며 PC 1440×900, 모바일 390×844에서 가로 넘침이 없어야 한다. 실제 구현과 API 갱신은 위 구현 이슈에서 수행한다.
