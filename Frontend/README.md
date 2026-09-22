# GRAB Frontend

React와 TypeScript로 만든 GRAB MVP 화면 초안입니다. 상품·인증·주문은 Mock 데이터를 사용하며,
백엔드 주문 ID가 있는 주문의 결제는 결제 API와 연동합니다. 로컬 데모 주문은 화면 확인을 위한
Mock 결제로 동작합니다.

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
- 마이페이지 결제 및 결제 성공·실패·확인 중 상태 표시
- 판매자 대시보드 및 DROP 등록 초안
- 판매자가 자유롭게 정의하는 옵션 그룹·옵션값·SKU 조합

알림 기능은 MVP 범위에서 제외했습니다.

## 코드 구조

- `src/components`: 도메인에 의존하지 않는 공통 UI
- `src/pages`: 라우트 단위 화면
- `src/features`: 인증, DROP, WISH, 주문, 판매자 기능
- `src/api`: Axios 기반 백엔드 API 클라이언트
- `src/types`: API 계약에 사용할 타입
- `src/utils`: 날짜와 가격 표시 함수
- `src/styles`: 전역 스타일

개발 서버에서는 `/api` 요청을 기본적으로 `http://localhost:8080`에 프록시합니다.
다른 서버를 사용할 때는 `VITE_API_BASE_URL`을 지정합니다.
