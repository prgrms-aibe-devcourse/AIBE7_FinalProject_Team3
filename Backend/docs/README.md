# GRAB 문서

## 개발 (`development/`)

| 문서 | 내용 |
| --- | --- |
| [REQUIREMENTS.md](development/REQUIREMENTS.md) | 기능·비기능 요구사항과 인수 조건 |
| [ERD.md](development/ERD.md) | 테이블 정의, 제약조건, 관계 |
| [API_SPEC.md](development/API_SPEC.md) | 공통 응답 규칙과 엔드포인트 명세 |
| [TECHSTACK.md](development/TECHSTACK.md) | 기술 선정, 인프라, CI/CD, 테스트 전략 |
| [CODING_CONVENTION.md](collaboration/CODING_CONVENTION.md) | 코드 작성 규칙 |

## 협업 (`collaboration/`)

| 문서 | 내용 |
| --- | --- |
| [PR_CONVENTION.md](collaboration/PR_CONVENTION.md) | 브랜치·커밋·Pull Request 규칙 |
| [PULL_REQUEST_TEMPLATE.md](collaboration/PULL_REQUEST_TEMPLATE.md) | PR 본문 템플릿 |

## 문서 규칙

- `assets/`: 문서에서 참조하는 이미지
- 문서 파일명은 `UPPER_SNAKE_CASE.md`, 각 문서는 `# 제목` 한 줄로 시작하고 본문은 `## 1.`부터 번호를 매긴다.
- 스키마·API 변경은 코드와 같은 PR에서 해당 문서를 함께 수정한다.
