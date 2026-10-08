# 소비자 상품 상세

- 경로: `/drops/:dropId` (`HashRouter`, 실제 URL은 `/#...`)
- 구현 이슈: GR-80 · GR-55 · GR-79
- [와이어프레임](../wireframes/DropDetailPage.html)

## 조회 계약

`GET /api/v1/drops/{dropId}`의 `galleryImages`, `detailImages`, `imageUrls`, `actions`, `serverTime`을 사용한다. 옵션별 재고 재조회는 `GET /api/v1/drops/{dropId}/stocks`를 사용한다. `imageUrls`는 GALLERY URL만 담는 구버전 호환 필드다.

## 화면 구성

- PC: 상단은 갤러리와 상품·구매 정보 2열, 하단은 실제 상품 설명·DETAIL 이미지·배송 안내 순서다. 첫 GALLERY 이미지는 목록 썸네일이다.
- 모바일: 갤러리→상품 정보→행동→설명·DETAIL→배송 순서로 한 열에 배치한다. DETAIL은 원본 비율로 자르지 않고 가로 폭 안에 맞춘다.

## 행동과 이동

갤러리 썸네일·키보드로 이미지를 바꾸고 서버 `actions`에 따라 WISH 등록/취소, 옵션·수량 선택과 주문, 종료 안내를 제공한다. 구매 전 최신 재고를 확인하되 최종 판단은 주문 API에 맡긴다.

## 상태와 예외

DETAIL이 없으면 텍스트 설명만 보인다. 이미지 실패 시 대체 설명을 제공하고 하단 이미지는 지연 로딩한다. DRAFT·없는 DROP은 404, CANCELED·ENDED는 상세는 보이되 구매 동작은 숨긴다. 요청 실패·인증 만료를 구분한다.

## 인수 조건

GALLERY만 썸네일에 쓰이고 DETAIL은 하단에 용도별 순서대로 나온다. WISH·GRAB·ENDED·CANCELED와 이미지 1장·DETAIL 없음·재고 0·모바일 가로 넘침을 확인한다.

공통: 로딩·빈 결과·실패·권한 오류를 구분하고 키보드 포커스와 직접 진입·뒤로 가기를 확인한다. 기존 색상·글꼴·이미지 비율을 유지하며 PC 1440×900, 모바일 390×844에서 가로 넘침이 없어야 한다. 실제 구현과 API 갱신은 위 구현 이슈에서 수행한다.
