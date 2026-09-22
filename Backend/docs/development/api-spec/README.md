# GRAB 전체 API 목록

> 상세 규칙과 요청·응답 예시는 아래 문서에서 확인합니다.

## 1. 문서 구성

| 문서 | 내용 |
| --- | --- |
| [COMMON.md](COMMON.md) | 공통 응답, 상태, 오류, 백그라운드 처리, 멱등성, 권한 표기 |
| [MEMBER_AUTH.md](MEMBER_AUTH.md) | 회원 및 인증 |
| [SELLER.md](SELLER.md) | 판매자 등록·승인 및 대시보드 |
| [DROP.md](DROP.md) | 공개·판매자 DROP 관리 |
| [WISH.md](WISH.md) | WISH |
| [ORDER.md](ORDER.md) | 주문 및 판매자 배송 관리 |
| [PAYMENT.md](PAYMENT.md) | 결제 |
| [INVENTORY.md](INVENTORY.md) | 재고 조회 |
| [IMAGE_UPLOAD.md](IMAGE_UPLOAD.md) | 이미지 업로드 |

## 2. 전체 API 목록 요약

### 2.1 회원 및 인증

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/signup` | 불필요 | 이메일과 비밀번호로 회원가입 |
| POST | `/api/v1/auth/login` | 불필요 | 로그인 및 Access/Refresh Token 발급 |
| POST | `/api/v1/auth/refresh` | Refresh Token | Access Token 재발급 |
| POST | `/api/v1/auth/logout` | 필요 | 로그아웃 및 Refresh Token 무효화 |
| GET | `/api/v1/users/me` | USER | 로그인한 사용자의 회원 정보 조회 |
| PATCH | `/api/v1/users/me` | USER | 이름, 연락처 등 본인 회원 정보 수정 |
| PATCH | `/api/v1/users/me/password` | USER | 본인 비밀번호 변경 |
| PUT | `/api/v1/users/me/profile-image` | USER | 본인 프로필 이미지 등록 또는 변경 |
| DELETE | `/api/v1/users/me/profile-image` | USER | 본인 프로필 이미지 삭제 |

### 2.2 판매자 등록 및 승인

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/v1/seller-applications` | USER | 일반 회원이 판매자 등록 신청 |
| GET | `/api/v1/seller-applications/me` | USER | 본인의 판매자 신청 상태 조회 |
| GET | `/api/v1/admin/seller-applications` | ADMIN | 판매자 신청 목록을 상태별로 조회 |
| POST | `/api/v1/admin/seller-applications/{applicationId}/approve` | ADMIN | 판매자 신청 승인 및 SELLER 기능 활성화 |
| POST | `/api/v1/admin/seller-applications/{applicationId}/reject` | ADMIN | 판매자 신청 반려 및 사유 기록 |

### 2.3 공개 DROP 탐색

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| GET | `/api/v1/categories` | 불필요 | 활성 카테고리 목록 조회 |
| GET | `/api/v1/drops` | 불필요 | 공개된 WISH·GRAB·ENDED DROP 목록 조회 |
| GET | `/api/v1/drops/{dropId}` | 불필요 | DROP 상세, 옵션, 일정 및 배송 정보 조회 |
| GET | `/api/v1/drops/{dropId}/stocks` | 불필요 | 옵션별 현재 가용 재고 조회 |

> SSE 재고 구독 API는 MVP 이후 도입 후보이며 위 MVP API 수에는 포함하지 않습니다.

**목록 API 지원 파라미터:**
- 상태 필터: `status`
- 카테고리 필터: `categoryId`
- 상품명 검색: `keyword`
- 품절 필터: `soldOut`
- 정렬: `sort`
- 페이지: `page`, `size`

### 2.4 판매자 DROP 관리

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/v1/seller/drops` | SELLER | DROP을 DRAFT 상태로 임시 저장 |
| GET | `/api/v1/seller/drops` | SELLER | 본인이 만든 DROP 목록 조회 |
| GET | `/api/v1/seller/drops/{dropId}` | SELLER | 본인이 만든 DROP 상세 조회 |
| PATCH | `/api/v1/seller/drops/{dropId}` | SELLER | DRAFT 상태 DROP 정보 수정 |
| POST | `/api/v1/seller/drops/{dropId}/publish` | SELLER | DRAFT DROP을 WISH 상태로 공개 |
| POST | `/api/v1/seller/drops/{dropId}/cancel` | SELLER | WISH 상태의 출시 취소 |
| GET | `/api/v1/seller/drops/{dropId}/stocks` | SELLER | 옵션별 가용·확보·판매 재고 조회 |
| GET | `/api/v1/seller/drops/{dropId}/wish-count` | SELLER | 해당 DROP의 활성 WISH 수 조회 |

> 모든 판매자 DROP API는 `SELLER` 권한과 리소스 소유권을 함께 검사합니다.

### 2.5 WISH

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| PUT | `/api/v1/drops/{dropId}/wish` | USER | WISH 상태의 DROP에 WISH 등록 |
| DELETE | `/api/v1/drops/{dropId}/wish` | USER | GRAB 시작 전 본인의 WISH 취소 |
| GET | `/api/v1/users/me/wishes` | USER | 본인이 등록한 WISH 목록 조회 |

> `PUT` 방식을 사용하므로 같은 사용자가 동일 DROP에 요청을 반복해도 활성 WISH는 한 개만 유지합니다.

### 2.6 주문 및 소비자 마이페이지

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/v1/orders` | USER | 옵션과 수량을 선택하여 주문 생성 및 재고 확보 |
| GET | `/api/v1/orders` | USER | 본인의 주문 목록 조회 |
| GET | `/api/v1/orders/{orderId}` | USER | 본인의 주문·결제·배송 상세 조회 |
| POST | `/api/v1/orders/{orderId}/cancel` | USER | 취소 가능한 본인 주문 취소 |

**주문 생성 시 서버 재검증 항목:**
- DROP의 현재 상태 및 판매 기간
- 옵션 존재 여부
- 실제 가용 재고
- 서버에 저장된 상품 가격
- 배송비 및 최종 결제 금액

### 2.7 결제

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/v1/orders/{orderId}/payments` | USER | 결제 대기 주문에 대해 Mock 결제 실행 |
| GET | `/api/v1/orders/{orderId}/payments` | USER/SELLER | 주문에 대한 결제 시도 및 결과 이력 조회 |
| POST | `/api/v1/payments/mock/webhook` | PG 검증 | Mock PG에서 전달한 결제 결과 수신 |

> 결제 결과 수신 시 주문번호, 금액, 결제 상태, 결제 유효시간을 검증합니다. 중복 통지가 도착해도 주문과 재고는 한 번만 변경합니다.

### 2.8 판매자 주문 및 배송 관리

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| GET | `/api/v1/seller/orders` | SELLER | 본인 DROP에 접수된 주문 목록 조회 |
| GET | `/api/v1/seller/orders/{orderId}` | SELLER | 본인 DROP의 주문 상세 조회 |
| POST | `/api/v1/seller/orders/{orderId}/prepare-shipment` | SELLER | 결제 완료 주문을 배송 준비 상태로 변경 |
| POST | `/api/v1/seller/orders/{orderId}/shipment` | SELLER | 택배사와 송장번호를 등록하고 발송 처리 |
| POST | `/api/v1/seller/orders/{orderId}/delivery-complete` | SELLER | 배송 중인 주문을 배송 완료 처리 |

> 판매자는 자신이 생성한 DROP의 주문만 조회하거나 변경할 수 있습니다.

### 2.9 판매자 대시보드

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| GET | `/api/v1/seller/dashboard/summary` | SELLER | DROP·주문·결제·재고 상태별 건수 조회 |
| GET | `/api/v1/seller/dashboard/drops` | SELLER | DROP별 WISH·주문·판매·재고 통계 조회 |
| GET | `/api/v1/seller/dashboard/upcoming-drops` | SELLER | 시작 또는 종료가 임박한 DROP 조회 |

### 2.10 이미지 업로드

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/v1/uploads/images/presigned-url` | SELLER | 상품 이미지 업로드용 Presigned URL 발급 |

> 발급된 URL로 스토리지에 이미지를 직접 업로드하고, 반환된 이미지 주소를 DROP 생성·수정 요청에 사용합니다.

---

## 3. 전체 API 간단 요약

| 영역 | API 수 | 주요 용도 |
| --- | --- | --- |
| 회원 및 인증 | 9 | 가입, 로그인, 토큰, 회원 정보 및 프로필 이미지 관리 |
| 판매자 등록 | 5 | 판매자 신청, 승인, 반려 |
| 공개 DROP 탐색 | 4 | 카테고리, 목록, 상세, 재고 조회 |
| 판매자 DROP 관리 | 8 | 생성, 수정, 공개, 취소, 통계 |
| WISH | 3 | 등록, 취소, 내 목록 |
| 주문 | 4 | 주문 생성, 조회, 취소 |
| 결제 | 3 | Mock 결제, 결과 수신, 이력 조회 |
| 배송 | 5 | 판매자 주문 조회 및 배송 상태 관리 |
| 대시보드 | 3 | 판매자 운영 통계 |
| 이미지 | 1 | 상품 이미지 업로드 |
| **합계** | **45** | |

---

