# GRAB PR 컨벤션

> 대상: 브랜치, 커밋 메시지, Pull Request
> 코드 작성 규칙은 [CODING_CONVENTION.md](CODING_CONVENTION.md)를 따른다.

## 1. 브랜치

- 통합 흐름과 CI 조건은 [TECHSTACK.md](../development/TECHSTACK.md) 5.2를 따른다.
- `main`은 개발 통합 브랜치이며, 이슈 브랜치는 `main`을 대상으로 Pull Request를 생성한다.
- `deploy`는 배포 브랜치이며, 배포할 때 `main`에서 `deploy`로 Pull Request를 생성한다.
- 브랜치명은 `{타입}/{이슈키}-{요약}` 형식으로 통일한다. 타입은 커밋 타입과 같은 값을 쓰고, 요약은 영문 소문자와 하이픈으로 적는다.

```text
feat/GR-12-drop-create-api
fix/GR-20-stock-rollback
docs/GR-31-convention
```

## 2. 커밋 메시지

```text
{타입}: {한국어 요약}
```

| 타입 | 용도 |
| --- | --- |
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 변경 |
| `refactor` | 동작 변화 없는 코드 개선 |
| `test` | 테스트 추가·수정 |
| `chore` | 빌드·설정·의존성 등 그 외 |

- 요약은 50자 이내, 마침표 없이 적는다.
- 하나의 커밋은 하나의 목적만 담는다. 기능 변경과 포매팅을 한 커밋에 섞지 않는다.
- 본문이 필요하면 빈 줄 뒤에 "왜 바꿨는지"를 적는다.

## 3. Pull Request

- 제목은 커밋 메시지와 같은 형식으로 적고, 본문에는 변경 내용·확인 방법·관련 이슈를 남긴다.
- 리뷰어 승인 1명 이상과 CI 통과 후 병합한다.
- 병합 후 작업 브랜치는 삭제한다.
- 본문 형식은 [PULL_REQUEST_TEMPLATE.md](PULL_REQUEST_TEMPLATE.md)를 복사해 사용한다.
