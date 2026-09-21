# GRAB Frontend

React와 TypeScript로 만든 GRAB MVP 화면 초안입니다. 현재는 Mock 데이터를 사용하며 백엔드 API 연동은 포함하지 않습니다.

## 실행

```bash
npm install
npm run dev
```

## 검증

```bash
npm test
npm run build
```

## 구현 범위

- WISH·GRAB 상품 탐색 및 상세
- WISH 목록과 데모 주문
- 판매자 대시보드 및 DROP 등록 초안
- 판매자가 자유롭게 정의하는 옵션 그룹·옵션값·SKU 조합

알림 기능은 MVP 범위에서 제외했습니다.

## 코드 구조

- `src/components`: 도메인에 의존하지 않는 공통 UI
- `src/pages`: 라우트 단위 화면
- `src/features`: 인증, DROP, WISH, 주문, 판매자 기능
- `src/types`: API 계약에 사용할 타입
- `src/utils`: 날짜와 가격 표시 함수
- `src/styles`: 전역 스타일

`api` 폴더는 실제 백엔드 연동과 OpenAPI 코드 생성을 도입할 때 추가합니다.
