# GRAB Frontend

React와 TypeScript로 만든 GRAB MVP 화면 초안입니다. 현재는 Mock 데이터를 사용하며 백엔드 API 연동은 포함하지 않습니다.

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
3. PR에 갱신 사유와 변경 전후 화면을 첨부합니다. 기준 이미지를 일괄 갱신해 의도하지 않은 변경을 통과시키지 않습니다.

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
- `src/types`: API 계약에 사용할 타입
- `src/utils`: 날짜와 가격 표시 함수
- `src/styles`: 전역 스타일

`api` 폴더는 실제 백엔드 연동과 OpenAPI 코드 생성을 도입할 때 추가합니다.
