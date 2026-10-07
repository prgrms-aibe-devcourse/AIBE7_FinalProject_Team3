# GRAB Frontend

React 19 + TypeScript + Vite 8 SPA. 현재는 Mock 데이터 기반 초안이며 백엔드 API 연동 전이다.
저장소 공통 규칙은 루트 `AGENTS.md`를 따르고, 이 문서는 `Frontend/` 작업에만 적용한다.

## 실행 및 검증

```bash
npm install
npm run dev      # 개발 서버
npm run lint
npm test         # Vitest (src/**/*.test.ts)
npm run build    # tsc --noEmit + vite build
```

- 변경 후 `lint`·`test`·`build`를 실행하고, 실행하지 못한 항목과 이유를 결과에 적는다.
- 포맷은 Prettier(`semi: false`, `singleQuote: true`)를 따른다.
- CI(`.github/workflows/frontend-ci.yml`)는 `Frontend/**` 변경 시에만 동작한다. 체크가 없는 것을 통과로 읽지 않는다.

## 구조

- `src/components` — 도메인에 의존하지 않는 범용 UI (`Header`, `Footer`, `EmptyState`)
- `src/features/<도메인>` — 도메인 UI·로직·Mock·테스트 (`drop`, `order`, `wish`, `auth`, `seller`)
- `src/pages/<Page>` — 라우트 단위 화면. 라우트는 `src/App.tsx`에서 관리한다.
- `src/types` — API 계약 타입, `src/utils` — 날짜·가격 표시 함수
- 라우터는 `HashRouter`다. URL은 `/#/wish` 형태다.
- 로그인·WISH·주문 상태는 `App.tsx`의 `useState`에만 있다. 새 전역 상태 관리 도입은 별도 합의 후 진행한다.

## 기술 스택

- `Backend/docs/development/TECHSTACK.md` 3절을 따른다.
- 스타일은 Tailwind 없이 `src/styles/global.css`의 CSS 변수(`--lime`, `--ink`, `--muted`, `--line`, `--soft`, `--danger`)와 기존 클래스를 사용한다.
- 새 의존성은 기존 코드·표준 API로 해결할 수 없을 때만 추가한다.

## UI 작성 규칙

- 새 UI를 만들기 전에 `src/components`, `src/features`의 기존 컴포넌트와 `global.css` 클래스를 먼저 찾아 재사용한다.
- 버튼·링크는 `primary-button` / `secondary-button` / `text-link` 클래스를 그대로 쓴다. `button`과 `Link`의 의미, `type`, `disabled`, 키보드 포커스를 유지한다.
- 빈 상태는 `EmptyState`를 사용한다.
- 같은 역할의 UI가 두 곳 이상에서 반복되면 컴포넌트로 추출한다. 마크업만 비슷하고 바뀌는 이유가 다르면 추출하지 않는다.
- CSS 클래스 하나로 공유되는 스타일(`primary-button` 등)은 컴포넌트로 감싸지 않는다.
- 도메인 UI는 `src/features/<도메인>`, 도메인에 의존하지 않는 UI는 `src/components`에 둔다. 범용 컴포넌트에 도메인 로직을 넣지 않는다.
- 쓰이지 않는 공통 컴포넌트나 과도한 다형성 API는 만들지 않는다.
- 현재 디자인(색상·글꼴·간격·이미지 비율·반응형 배치)을 유지한다. 리디자인은 별도 이슈로 다룬다.
- PC(1440×900)와 모바일(390×844)에서 가로 넘침이 없는지 확인한다.

## 판매자 대시보드 계열 (디자인 개선 예정)

- `SellerDashboardPage`, `SellerDropDetailPage`는 디자인을 개선할 예정이다.
- 이 화면의 UI(`stats-grid`, DROP 관리 목록, 기간 실적, 판매 완료 패널, 옵션별 재고 표)를 공통 컴포넌트로 추출하거나 다른 화면의 패턴으로 확산하지 않는다.
- 공통 컴포넌트나 공유 CSS(`seller-title`, `panel`, `status-pill` 등)를 바꿀 때 이 화면들이 달라지지 않게 호환을 유지한다.

## 사양 연동

- 요구사항·API 명세는 `Backend/docs/development/`(REQUIREMENTS.md, api-spec/)를 확인한다.
- Mock 타입과 주석에 적힌 API 경로·이슈 번호(GR-xx)는 연동 시 기준이 된다. 함부로 바꾸지 않는다.
- 코드와 사양이 어긋나면 코드를 먼저 고치지 말고 문서를 수정할지 먼저 묻는다.

## 금지

- 커밋·푸시는 사용자가 요청할 때만 한다.
- `dist/`, `node_modules/`는 읽거나 수정하지 않는다.
- `todo/`는 로컬 작업 메모다(git 제외). 저장소 문서로 취급하지 않는다.
