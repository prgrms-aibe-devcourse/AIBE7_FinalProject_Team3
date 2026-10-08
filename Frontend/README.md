# GRAB Frontend

React와 TypeScript로 만든 GRAB MVP 화면 초안입니다. 주요 화면은 Mock 데이터를 사용하며 공개 카테고리 API만 최소 연동했습니다.

## 실행

```bash
npm install
npm run dev
```

## 검증

```bash
npm run lint
npm test         # Vitest 단위 테스트
npm run build
npm run e2e      # Playwright E2E (시각 비교 제외)
```

`npm install` 시 Git pre-commit 훅(husky)이 설치되어, 커밋할 때 스테이징한 `Frontend/` 파일에 Prettier가 자동 적용됩니다(lint-staged). CI는 `npm run format:check`로 포맷을 검사합니다.

## E2E·시각 회귀 테스트

Playwright로 핵심 흐름, PC(1440×900)·모바일(390×844) 가로 넘침, 화면 기준 스크린샷을 검사합니다.

### 설치

```bash
npm ci
npx playwright install chromium
```

테스트는 Vite 개발 서버(5174 포트)를 자동으로 띄웁니다. 시각은 `2026-10-01 10:00 KST`로 고정되고, 외부 이미지는 로컬 이미지로 대체되며 Google Fonts 등 나머지 외부 요청은 차단됩니다(`e2e/fixtures.ts`).

### 실행

| 명령                                       | 용도                                                       |
| ------------------------------------------ | ---------------------------------------------------------- |
| `npm run e2e`                              | 로컬 실행. `@visual` 시각 비교는 제외                      |
| `npm run e2e:docker`                       | CI와 같은 Playwright Docker 이미지에서 시각 비교 포함 전체 |
| `npm run e2e:docker -- --grep @visual`     | 시각 비교만                                                |
| `npm run e2e:docker -- --update-snapshots` | 기준 이미지 갱신                                           |

기준 이미지(`e2e/visual.spec.ts-snapshots/*-linux.png`)는 리눅스 Docker 환경 기준입니다. 맥·윈도에서는 글꼴 렌더링이 달라 맞지 않으므로 시각 비교는 `e2e:docker`로만 실행합니다. Docker 이미지 버전은 `package.json`의 `@playwright/test` 버전과 같아야 합니다.

### 실패 분석

```bash
npx playwright show-report                          # HTML 리포트
npx playwright show-trace test-results/<테스트>/trace.zip
```

- 시각 비교가 실패하면 `test-results/`에 기준(expected)·실제(actual)·차이(diff) 이미지가 생깁니다. HTML 리포트에서 나란히 볼 수 있습니다.
- CI에서는 `Frontend CI` 워크플로 실행 결과의 `playwright-report` 산출물을 내려받아 확인합니다.

### 기준 이미지 갱신

1. 의도한 디자인 변경인지 실패 diff로 먼저 확인합니다. 의도하지 않은 변경이면 코드를 고칩니다.
2. 바뀐 화면만 갱신합니다. 예: `npm run e2e:docker -- --grep login --update-snapshots`
3. PR 본문에 갱신 사유를 적습니다. 변경 전후 화면은 PR의 Files changed에서 이미지 diff(2-up·Swipe·Onion skin)로 확인하므로 따로 첨부하지 않습니다.
4. 기준 이미지를 일괄 갱신해 의도하지 않은 변경을 통과시키지 않습니다.

판매자 대시보드·판매자 DROP 상세는 디자인 개선 예정이라 기준 이미지 대상이 아닙니다.

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
- `src/api`: 공통 요청 클라이언트, 도메인별 API 요청, 생성 스키마
- `src/types`: 화면·Mock 데이터용 타입
- `src/utils`: 날짜와 가격 표시 함수
- `src/styles`: 전역 스타일

## Mock 화면과 API 연동 경계

페이지별 화면 작업은 `docs/pages/<PageName>.md`와 `docs/wireframes/<PageName>.html`을 기준으로 합니다. 이 단계에서는 새 API 요청을 붙이지 않고, 화면에 필요한 예시 데이터와 데모 동작을 `src/features/<도메인>`의 Mock 파일에 모읍니다. 페이지와 재사용 UI에 긴 예시 객체를 흩어 놓지 않습니다. 현재 사용 중인 공개 카테고리 API는 그대로 유지합니다.

- 화면은 도메인 타입과 props를 사용해 표시합니다. 기존 컴포넌트를 재사용하고, 나중의 API 호출을 위해 쓰이지 않는 요청 계층이나 추상 인터페이스를 미리 만들지 않습니다.
- 로딩·빈 결과·오류·권한 부족·상태별 버튼의 화면 상태를 준비합니다. Mock 버튼은 로컬 데모 동작이며 실제 주문·결제·판매자 승인·배송 처리로 안내하지 않습니다.
- 실제 연동 이슈에서는 Mock 공급 부분을 `src/api/client.ts` 요청과 TanStack Query로 교체하고, API 응답을 화면용 데이터로 바꾸는 코드는 해당 `src/features`에 둡니다. 쓰이지 않게 된 Mock만 제거하고 화면 배치와 컴포넌트는 유지합니다.
- 인증 쿠키·CSRF·401 재시도, 저장 결과·서버 오류·중복 요청 검증은 실제 API 연동 단계에서 확인합니다. 화면 초안의 Mock 성공 상태를 서버 처리 성공의 증거로 취급하지 않습니다.

화면별 PR 범위와 검증 항목은 해당 Linear 이슈에, 로컬 작업 순서는 `todo/page-renewal.md`에 적습니다. `todo/`는 git에서 제외되는 개인 작업 메모입니다.

## API 계약 및 로컬 확인

백엔드를 먼저 실행한 뒤 `npm run dev`로 프론트엔드를 띄웁니다. 프론트엔드는 `/api/v1` 상대 경로로 요청하고 Vite가 로컬 백엔드 `http://localhost:8080`에 프록시합니다. 공개 카테고리 조회(`GET /api/v1/categories`)는 `src/api/category/categories.ts`에서 생성 타입을 사용합니다. 화면에 남은 Mock 상품 데이터의 실제 API 전환은 화면 작업과 분리한 후속 이슈에서 진행합니다.

```bash
npm run api:snapshot
npm run api:generate
```

`api:snapshot`은 백엔드의 `/v3/api-docs/{도메인}`을 `openapi/*.json`에 저장하고, `api:generate`는 `redocly.yaml`에 정의한 출력 경로에 `src/api/schema/*.ts`를 생성합니다. 새 도메인을 추가할 때는 백엔드 `springdoc.group-configs`, `scripts/update-openapi.mjs`, `redocly.yaml`에 같은 그룹을 추가합니다. 스냅샷과 생성 타입을 함께 커밋하고 생성 파일은 직접 수정하지 않습니다. `src/api/client.ts`는 공통 요청 주소를 지정하며 Axios가 HTTP 오류를 거부합니다. 각 요청 함수는 성공 응답의 `data`를 확인하고 반환합니다. 화면용 모델 변환은 요청 함수가 아닌 해당 `src/features`에서 합니다. 서버 응답 캐시는 TanStack Query를 사용합니다. 인증 쿠키·CSRF·401 재시도는 GR-45에서 구현합니다.

명세가 바뀌면 위 명령으로 다시 생성한 후 `git diff -- openapi src/api/schema`를 확인합니다. 대상 경로와 응답 형태를 `Backend/docs/development/api-spec/`의 Markdown 명세와 비교하고, 불일치하면 구현 전에 사양 방향을 확인합니다. 로컬 실행 확인은 프론트엔드 개발 서버가 켜진 상태에서 브라우저로 `http://localhost:5173/api/v1/categories`를 열어 `success: true`와 카테고리 배열을 확인합니다.
