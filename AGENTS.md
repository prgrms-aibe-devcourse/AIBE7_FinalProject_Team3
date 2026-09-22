# GRAB

한정 판매(DROP) 커머스 서비스. 모노레포 구성이다.

- `Backend/` — Spring Boot 4.1 / Java 17.
- `Frontend/` — 초안 구현 상태. 구현 및 보완 시 `Backend/docs/development/TECHSTACK.md` 3절의 스택을 따른다.

답변과 커밋 메시지는 한국어로 작성한다.

## 실행 및 검증

- 백엔드는 `Backend/`, 프론트엔드는 `Frontend/`에서 실행한다.
- 실행 방법은 각 디렉터리의 README와 빌드 설정을 확인한다.
- 변경한 영역의 관련 검증을 수행하고, 미실행 항목과 이유를 결과에 명시한다.

## 사양 문서 (구현 전 확인)

- 요구사항 및 인수 조건(AUTH-001 등 ID 체계): `Backend/docs/development/REQUIREMENTS.md`
- 스키마: `Backend/docs/development/ERD.md`
- API 명세: `Backend/docs/development/api-spec/README.md`
- 기술 선정 근거: `Backend/docs/development/TECHSTACK.md`

구현 내용이 문서와 어긋나면 코드를 먼저 고치지 말고, 문서를 수정할지 먼저 물어본다.

## 협업 규약

- 코딩 컨벤션: `Backend/docs/collaboration/CODING_CONVENTION.md`
- 브랜치·커밋·Pull Request 컨벤션: `Backend/docs/collaboration/PR_CONVENTION.md`

관련 작업 전에 해당 문서를 확인하고 따른다.

## 금지

- 이미 커밋된 Flyway 마이그레이션 파일은 수정하지 않는다. 새 버전 파일을 추가한다.
- `build.gradle`에 주석 처리된 Spring AI 의존성을 임의로 활성화하지 않는다 (도입 보류 상태).
- 재고 차감과 주문 생성은 PostgreSQL 트랜잭션 + 행 잠금(`SELECT ... FOR UPDATE`)으로 한 번에 처리한다.
  Redis는 캐시 용도로만 쓰고, 재고 차감의 판단 근거로 삼지 않는다 (Redis를 통째로 날려도 PostgreSQL만으로 복구 가능해야 한다).
- `Backend/build/` 는 빌드 산출물이므로 읽지 않는다.

## 주의

- CI(`.github/workflows/backend-ci.yml`)는 `develop, main` 대상 푸시·PR에서 `Backend/**`가 변경됐을 때만 동작한다.
  조건을 벗어나면 워크플로가 아예 실행되지 않으므로, 체크가 없는 것을 통과로 읽지 않는다.
- 이미지 배포(`.github/workflows/publish-backend.yml`)는 `main` 푸시에서 동작한다.

