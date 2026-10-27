# GRAB 전체 API 목록

> 상세 규칙과 요청·응답 예시는 아래 문서에서 확인합니다.

실행 중인 백엔드의 OpenAPI 문서는 `/v3/api-docs`에서 전체를, `/v3/api-docs/{group}`에서 도메인별로 확인할 수 있습니다. 그룹은 `category`, `dashboard`, `drop`, `order`, `payment`, `user`, `wish`이며 컨트롤러 패키지를 기준으로 자동 구성됩니다. Swagger UI(`/swagger-ui/index.html`)에서도 그룹을 선택할 수 있습니다. 새 도메인을 만들면 `application.yml`의 `springdoc.group-configs`에 등록합니다. CSRF 토큰 발급(`auth` 패키지)은 `user` 그룹에 포함됩니다. Mock 배송 API를 활성화하면 `order` 그룹에 포함됩니다.

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
| [DEMAND.md](DEMAND.md) | 판매자 수요 분석·판매 계획 |

## 2. 전체 API 목록 요약

### 2.1 회원 및 인증

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| GET | `/api/v1/auth/csrf` | 불필요 | CSRF 토큰 쿠키 발급 |
| POST | `/api/v1/auth/email-verification` | 불필요 | LOCAL 회원가입용 이메일 인증 코드 발송 |
| POST | `/api/v1/auth/email-verification/confirm` | 불필요 | 이메일 인증 코드 확인 및 이메일 가입 토큰 발급 |
| POST | `/api/v1/auth/signup` | 이메일 가입 토큰 | 인증된 이메일과 비밀번호로 LOCAL 회원가입 완료 |
| POST | `/api/v1/auth/login` | 불필요 | 로그인 및 Access/Refresh Token 발급 |
| GET | `/api/v1/auth/oauth2/{provider}` | 불필요 | KAKAO·GOOGLE 소셜 로그인 시작 |
| GET | `/api/v1/auth/oauth2/{provider}/callback` | 불필요 | 소셜 로그인 콜백 처리 (제공자 호출) |
| POST | `/api/v1/auth/oauth2/signup` | 소셜 가입 토큰 | 최초 소셜 인증 회원의 가입 완료 |
| POST | `/api/v1/auth/refresh` | Refresh Token | Access/Refresh Token 재발급 (Refresh Token Rotation) |
| POST | `/api/v1/auth/logout` | Refresh Token(선택) | 로그아웃, Refresh Token 무효화 및 인증 쿠키 만료 |
| GET | `/api/v1/users/me` | USER | 로그인한 사용자의 회원 정보 조회 |
| PATCH | `/api/v1/users/me` | USER | 본인 닉네임 수정 |
| PATCH | `/api/v1/users/me/password` | USER | 본인 비밀번호 변경 |
| POST | `/api/v1/users/me/profile-image/upload-url` | USER | 본인 프로필 이미지 업로드용 Supabase 서명 업로드 URL 발급 |
| PUT | `/api/v1/users/me/profile-image` | USER | 본인 프로필 이미지 등록 또는 변경 |
| DELETE | `/api/v1/users/me/profile-image` | USER | 본인 프로필 이미지 삭제 |

> 프로필 이미지 업로드 URL 발급은 [IMAGE_UPLOAD.md 1.2절](IMAGE_UPLOAD.md#12-내-프로필-이미지-업로드-url-발급), 등록·삭제는 [MEMBER_AUTH.md 1.12·1.13절](MEMBER_AUTH.md#112-내-프로필-이미지-등록수정)을 따릅니다. 세 API 모두 USER 권한과 CSRF 토큰이 필요합니다.

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
| GET | `/api/v1/drops/stock-stream?dropIds=100,101` | 불필요. 무효 쿠키는 401 | 단일·다중 상품 변경 구독. 커밋 후 도메인 자동 알림 |

> GR-110에서 SSE endpoint·전송·커밋 후 도메인 변경 알림·OpenAPI 스냅샷·프론트 생성 타입을 구현했습니다. 화면 구독은 GR-111/112, 프록시·운영 검증은 GR-180 범위이고 다중 서버 Redis 전달은 보류했습니다(GR-113 취소). 변경 알림 후 기존 조회 API를 재사용하는 정책과 API·복구·초기 운영 목표는 [실시간 변경 전달 계약](../REALTIME_STOCK.md)을 따릅니다. 운영 성능은 미실측입니다.

**목록 API 지원 파라미터:**
- 상태 필터: `status`
- 카테고리 필터: `categoryId`
- 상품명 검색: `keyword`
- 품절 필터: `soldOut`
- 정렬: `sort`
- 페이지: `page`, `size`

`sort=wishCount,desc`는 WISH, `sort=soldQuantity,desc`는 GRAB 인기 조회에 사용합니다. 메인은 각 영역 최대 8개, 상품 목록은 24개/페이지로 요청합니다. 상세 조건은 [DROP.md](DROP.md) 1.2를 따릅니다.

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
| POST | `/api/v1/seller/drop-drafts` | SELLER | 업로드한 사진으로 상품명·설명·카테고리·대체 설명 AI 초안 생성 (저장하지 않음) |

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
| POST | `/api/v1/orders/{orderId}/payments` | USER | 결제창 인증 결과(`paymentKey`)로 토스페이먼츠 테스트 결제 승인 |
| GET | `/api/v1/orders/{orderId}/payments` | USER | 본인 주문의 결제 시도 및 결과 이력 조회 |
| POST | `/api/v1/payments/toss/webhook` | 토스 조회 재확인 | 토스페이먼츠 결제 상태 변경 웹훅 수신 (후속 구현) |

> Mock 결제는 토스페이먼츠 테스트 환경(테스트 API 키)으로 진행하며 실제 결제는 일어나지 않습니다.
> 결제 결과 반영 시 주문번호, 금액, 결제 상태, 결제 유효시간을 검증합니다. 같은 결과가 반복 도착해도 주문과 재고는 한 번만 변경합니다.

### 2.8 판매자 주문 및 배송 관리

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| GET | `/api/v1/seller/orders` | SELLER | 본인 DROP에 접수된 주문 목록 조회 |
| GET | `/api/v1/seller/orders/{orderId}` | SELLER | 본인 DROP의 주문 상세 조회 |
| GET | `/api/v1/seller/orders/{orderId}/payments` | SELLER | 본인 DROP 주문의 결제 시도 및 결과 이력 조회 (PG 실패 사유 제외) |
| POST | `/api/v1/seller/orders/{orderId}/prepare-shipment` | SELLER | 결제 완료 주문을 배송 준비 상태로 변경 |
| POST | `/api/v1/seller/orders/{orderId}/shipment` | SELLER | 택배사와 송장번호를 등록하고 발송 처리 |
| PATCH | `/api/v1/seller/orders/{orderId}/shipment` | SELLER | 등록한 택배사·송장번호 수정 (`SHIPPED`만) |
| POST | `/api/v1/mock/orders/{orderId}/delivery/complete` | 로컬·테스트 전용 | Mock 배송 완료 결과를 주문에 반영 |

> 판매자는 자신이 생성한 DROP의 주문만 조회하거나 변경할 수 있습니다.
> Mock 배송 API는 `GRAB_MOCK_DELIVERY_ENABLED=true`일 때만 활성화합니다. 로컬·테스트 환경에서만 켜고 운영 환경에서는 기본값(`false`)을 유지합니다.

### 2.9 판매자 대시보드

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| GET | `/api/v1/seller/dashboard/summary` | SELLER | DROP·주문·결제·재고 상태별 건수 조회 |
| GET | `/api/v1/seller/dashboard/drops` | SELLER | DROP별 WISH·주문·판매·재고 통계 조회 |
| GET | `/api/v1/seller/dashboard/upcoming-drops` | SELLER | 시작 또는 종료가 임박한 DROP 조회 |

### 2.10 이미지 업로드

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/v1/uploads/images/presigned-url` | SELLER | 상품 이미지 업로드용 Supabase 서명 업로드 URL 발급 |

> 발급된 `uploadUrl`로 클라이언트가 Supabase Storage에 이미지를 직접 업로드하고, 응답의 `{ imageId, imageUrl }`을 DROP 생성·수정 요청의 GALLERY `images` 또는 DETAIL `detailImages`에 사용합니다. DROP당 GALLERY는 최대 5개, DETAIL은 최대 15개입니다.

### 2.11 판매자 수요 분석 (GR-140)

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| GET | `/api/v1/seller/drops/{dropId}/demand-analysis` | SELLER | 수요 데이터와 기본 가정의 판매 계획 조회 |
| POST | `/api/v1/seller/drops/{dropId}/demand-analysis/calculate` | SELLER | 가정을 바꿔 판매 계획 재계산 (저장하지 않음) |
| POST | `/api/v1/seller/drops/{dropId}/demand-analysis/explanation` | SELLER | 판매자가 고른 가정의 분석을 LLM으로 설명 (요청 시에만, 같은 입력이면 저장된 설명 재사용) |
| POST | `/api/v1/seller/stock-recommendations` | SELLER | DROP 생성 단계 재고 추천·가격대 참고 (저장하지 않음) |

> 계산은 서버가 하고 LLM은 계산 결과의 설명만 만듭니다. 분석과 추천은 참고용이며 재고·가격·판매 상태를 바꾸지 않습니다. 판매 계획 저장·결정 API는 GR-175에서 제거했습니다. 다른 판매자의 판매 데이터는 익명 집계값으로만 사용합니다. 구현 시 OpenAPI `demand` 그룹을 추가합니다.

---

## 3. 전체 API 간단 요약

| 영역 | API 수 | 주요 용도 |
| --- | --- | --- |
| 회원 및 인증 | 16 | 가입, 로그인, 소셜 로그인, 토큰, 회원 정보 및 프로필 이미지 관리 |
| 판매자 등록 | 5 | 판매자 신청, 승인, 반려 |
| 공개 DROP 탐색 | 5 | 카테고리, 목록, 상세, 재고 조회·변경 구독 |
| 판매자 DROP 관리 | 9 | 생성, 수정, 공개, 취소, 통계, 사진 기반 AI 초안 |
| WISH | 3 | 등록, 취소, 내 목록 |
| 주문 | 4 | 주문 생성, 조회, 취소 |
| 결제 | 3 | 토스페이먼츠 테스트 결제 승인, 웹훅 수신, 이력 조회 |
| 배송 | 7 | 판매자 주문 조회 및 배송 상태 관리 |
| 대시보드 | 3 | 판매자 운영 통계 |
| 이미지 | 1 | 상품 이미지 업로드 |
| 수요 분석 | 4 | 수요 분석·재계산·설명, 생성 단계 재고 추천 |
| **합계** | **60** | |

---
