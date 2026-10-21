# 판매자 등록 신청

- 경로(계획): `/seller/apply`
- 요구사항: SELLER-001, SELLER-003
- [와이어프레임](../wireframes/SellerApplyPage.html)

## 조회·처리 계약

`GET /api/v1/seller-applications/me`로 기존 신청을 확인한다(없으면 404). 신청이 없거나 `REJECTED` 상태인 회원만 서류를 먼저 업로드한 뒤 `POST /api/v1/seller-applications`를 호출한다. 권한은 `GET /api/v1/users/me`의 역할과 서버 응답을 따른다. 명세는 `Backend/docs/development/api-spec/SELLER.md` 1.1~1.3절을 따른다.

- `POST /api/v1/seller-documents`(multipart, `documentType` + `file`)를 4종(`BUSINESS_LICENSE`/`ID_CARD`/`BANKBOOK`/`MAIL_ORDER_BUSINESS_REPORT`) 모두 호출해 각각 `documentId`를 받는다. `ID_CARD`는 주민등록번호 뒷자리를 가린 이미지를 올려야 한다는 안내를 폼에 둔다.
- 4종 `documentId`가 모두 모이면 `POST /api/v1/seller-applications`에 `brandName`, `contactEmail`, `businessRegistrationNumber`, `description`, `documentIds`를 보낸다.

## 화면 구성

- 신청 전(미신청·`REJECTED`)에는 소개와 서류 업로드 4종 + 필수 입력 폼, 신청 후(`PENDING`·`APPROVED`)에는 브랜드 정보와 상태를 표시한다.
- 서류 업로드는 항목별로 업로드 상태(미완료/완료/실패)를 보여주고, 4종이 모두 완료되기 전에는 제출 버튼을 비활성화한다.
- PC는 안내와 폼 2열, 모바일은 안내→폼/상태 한 열이다.

## 행동과 이동

로그인하지 않았으면 로그인으로 이동한 뒤 신청 화면으로 돌아온다. `PENDING`이면 중복 신청 버튼을 숨기고 승인 대기를 안내한다. `APPROVED`면 판매자 작업 공간으로 이동한다. `REJECTED`면 반려 상태를 안내하고 서류 업로드·입력 폼을 다시 제공해 재신청을 받는다(재신청은 새 신청이 아니라 같은 신청 건을 `PENDING`으로 갱신하는 `POST /api/v1/seller-applications` 재호출이다).

> `GET /api/v1/seller-applications/me` 응답에 반려 사유(`rejectedReason`)가 아직 없어(백엔드 미저장, 관련 이슈 GR-107) 현재는 "반려됨" 상태만 보여주고 사유 텍스트는 표시하지 않는다. GR-107 반영 후 이 화면에 사유 표시를 추가한다.

## 상태와 예외

필수 입력·서류 4종 누락(`400 VALIDATION_FAILED`), 휴·폐업 사업자번호(`422 BUSINESS_NOT_ACTIVE`), 중복 신청(`409 SELLER_APPLICATION_ALREADY_EXISTS`), 중복 사업자등록번호(`409 DUPLICATE_BUSINESS_REGISTRATION_NUMBER`), 권한 오류, 조회·제출 실패를 구분한다. 서류 업로드 실패(예: 이미지로 디코딩 안 되는 파일, `400 VALIDATION_FAILED`)는 해당 항목만 다시 시도할 수 있게 한다. 신청 응답만으로 판매자 권한을 부여하지 않고 서버가 승인한 역할을 다시 확인한다.

## 인수 조건

미신청·대기·승인·반려(및 반려 후 재신청)·서류 업로드 4종 완료 전 제출 차단·비회원·중복 제출·모바일 가로 넘침을 확인한다.

공통: 로딩·실패·권한 오류를 구분하고 키보드 포커스와 직접 진입·뒤로 가기를 확인한다. [공통 디자인 기준](../DESIGN.md)을 따르며 PC 1440×900, 모바일 390×844에서 가로 넘침이 없어야 한다. 실제 구현은 별도 이슈에서 수행한다.

## 디자인 적용 (2026-10-13)

진한 소개 패널에 기존 GRAB 심볼을 넣고 입력·신청 결과는 흰색 패널에 표시한다. 모바일에서도 소개 문구와 심볼이 겹치지 않게 배치한다.

[공통 디자인 기준](../DESIGN.md)을 함께 따른다.

## 헤더·판매자 진입 (GR-128)

일반 화면과 `/seller/apply`는 WISH·GRAB 기본 헤더를 사용한다. `users/me.roles`에 SELLER가 있을 때만 그 옆에 SELLER 링크(`/seller`)를 표시한다. 일반 회원은 MY → 판매자 신청 → 운영자 승인 순서로 권한을 취득하며 제출 성공·신청 상태만으로 권한을 추정하지 않는다. 판매자 작업 공간 헤더는 DASHBOARD·DROPS·ORDERS·INVENTORY·SALES와 SHOP(`/wish`) 복귀 링크를 사용한다. 인증 로딩·로그아웃·계정 변경 시에도 실제 역할을 따른다.
