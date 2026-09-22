# GRAB API 명세서

## 1. 공통 규칙

### 1.1 기본 정보

```yaml
Base URL: /api/v1
Content-Type: application/json
인증 방식: Bearer Access Token
시간 형식: ISO 8601 (예: 2026-09-18T14:00:00+09:00)
금액 단위: KRW, 정수
페이지 번호: 0부터 시작
```

### 1.2 인증 헤더

```http
Authorization: Bearer {accessToken}
```

### 1.3 공통 성공 응답

```json
{
  "success": true,
  "data": {},
  "message": null
}
```

### 1.4 공통 오류 응답

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ERROR_CODE",
    "message": "오류 메시지",
    "fieldErrors": [
      {
        "field": "email",
        "reason": "올바른 이메일 형식이 아닙니다."
      }
    ]
  }
}
```

### 1.5 공통 HTTP 상태 코드

| 상태 코드 | 의미 |
| --- | --- |
| 200 | 요청 성공 |
| 201 | 리소스 생성 성공 |
| 204 | 요청 성공, 응답 본문 없음 |
| 400 | 잘못된 요청 또는 유효성 검증 실패 |
| 401 | 인증 필요 또는 토큰 오류 |
| 403 | 권한 또는 소유권 없음 |
| 404 | 리소스를 찾을 수 없음 |
| 409 | 중복 요청 또는 상태 충돌 |
| 422 | 비즈니스 규칙 위반 |
| 429 | 요청 횟수 제한 초과 |
| 500 | 서버 내부 오류 |

### 1.6 페이지 응답

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 100,
  "totalPages": 5,
  "hasNext": true
}
```

---

## 2. 주요 상태 정의

### 2.1 회원 역할

| 역할 | 설명 |
| --- | --- |
| `USER` | 모든 활성 회원에게 부여되는 기본 권한 |
| `SELLER` | `sellers.status = APPROVED`일 때 파생되는 판매자 권한 |
| `ADMIN` | `users.role = ADMIN`인 운영자 권한 |

> API의 `roles`는 유효 권한 목록입니다. `SELLER`는 `users.role`에 중복 저장하지 않고 승인된 판매자 상태에서 계산합니다.

### 2.2 판매자 신청 상태

| 상태 | 설명 |
| --- | --- |
| `PENDING` | 승인 대기 |
| `APPROVED` | 승인 |
| `REJECTED` | 반려 |

### 2.3 DROP 상태

| 상태 | 설명 |
| --- | --- |
| `DRAFT` | 임시 저장 |
| `WISH` | 공개 및 WISH 등록 가능 |
| `GRAB` | 판매 진행 중 |
| `ENDED` | 판매 종료 |
| `CANCELED` | 출시 취소 |

> 품절 여부는 DROP 상태와 별개인 `soldOut` 값으로 표현합니다.

### 2.4 주문 상태

| 상태 | 설명 |
| --- | --- |
| `PAYMENT_PENDING` | 결제 대기 |
| `PAID` | 결제 완료 |
| `PREPARING` | 배송 준비 |
| `SHIPPED` | 배송 중 |
| `DELIVERED` | 배송 완료 |
| `CANCELED` | 취소 완료 |
| `EXPIRED` | 결제 기한 만료 |

> 개별 결제 시도의 실패는 결제 상태로 기록하며 주문은 결제 마감 전까지 `PAYMENT_PENDING`을 유지할 수 있습니다.

### 2.5 결제 상태

| 상태 | 설명 |
| --- | --- |
| `PENDING` | 결제 시도 중 |
| `SUCCEEDED` | 결제 성공 |
| `FAILED` | 결제 실패 |
| `UNKNOWN` | 외부 PG 결과 확인 필요 |
| `CANCELED` | 결제 취소 완료 |

> 보정 필요 여부는 결제 상태와 분리된 `reconciliationStatus`(`NONE`, `REQUIRED`, `RESOLVED`)로 표현합니다.

---

## 3. 회원 및 인증 API

### 3.1 회원가입

```http
POST /api/v1/auth/signup
```

- **인증**: 불필요

**요청:**

```json
{
  "email": "user@example.com",
  "password": "Password123!",
  "displayName": "홍길동",
  "phone": "01012345678"
}
```

**응답:** `201 Created`

```json
{
  "success": true,
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "displayName": "홍길동",
    "roles": ["USER"],
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

**검증 규칙:**
- 이메일은 유효한 형식이어야 한다.
- 이메일은 중복될 수 없다.
- 비밀번호는 최소 8자 이상이어야 한다.
- 비밀번호는 영문, 숫자, 특수문자를 포함해야 한다.

**오류 코드:**
- `INVALID_EMAIL`
- `DUPLICATE_EMAIL`
- `INVALID_PASSWORD`

### 3.2 로그인

```http
POST /api/v1/auth/login
```

- **인증**: 불필요

**요청:**

```json
{
  "email": "user@example.com",
  "password": "Password123!"
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "tokenType": "Bearer",
    "accessToken": "access-token",
    "refreshToken": "refresh-token",
    "expiresIn": 3600,
    "user": {
      "userId": 1,
      "email": "user@example.com",
      "displayName": "홍길동",
      "roles": ["USER"]
    }
  }
}
```

**오류 코드:**
- `INVALID_CREDENTIALS`
- `ACCOUNT_DISABLED`

### 3.3 토큰 재발급

```http
POST /api/v1/auth/refresh
```

- **인증**: Refresh Token

**요청:**

```json
{
  "refreshToken": "refresh-token"
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "tokenType": "Bearer",
    "accessToken": "new-access-token",
    "refreshToken": "new-refresh-token",
    "expiresIn": 3600
  }
}
```

**오류 코드:**
- `INVALID_TOKEN`
- `TOKEN_EXPIRED`

### 3.4 로그아웃

```http
POST /api/v1/auth/logout
```

- **인증**: 필요

**요청:**

```json
{
  "refreshToken": "refresh-token"
}
```

**응답:** `204 No Content`

### 3.5 내 정보 조회

```http
GET /api/v1/users/me
```

- **인증**: 필요 (`USER`)

**응답:**

```json
{
  "success": true,
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "displayName": "홍길동",
    "phone": "01012345678",
    "roles": ["USER", "SELLER"],
    "profileImageUrl": "https://cdn.example.com/images/profile.jpg",
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 3.6 내 정보 수정

```http
PATCH /api/v1/users/me
```

- **인증**: 필요 (`USER`)

**요청:**

```json
{
  "displayName": "김길동",
  "phone": "01098765432"
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "displayName": "김길동",
    "phone": "01098765432",
    "roles": ["USER", "SELLER"],
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 3.7 비밀번호 변경

```http
PATCH /api/v1/users/me/password
```

- **인증**: 필요 (`USER`)

**요청:**

```json
{
  "currentPassword": "Password123!",
  "newPassword": "NewPassword456!"
}
```

**응답:** `204 No Content`

**오류 코드:**
- `INVALID_PASSWORD`
- `PASSWORD_MISMATCH`

### 3.8 내 프로필 이미지 등록/수정

```http
PUT /api/v1/users/me/profile-image
```

- **인증**: 필요 (`USER`)

**요청:**

```json
{
  "imageUrl": "https://cdn.example.com/images/profile-uuid.jpg"
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "userId": 1,
    "profileImageUrl": "https://cdn.example.com/images/profile-uuid.jpg"
  }
}
```

### 3.9 내 프로필 이미지 삭제

```http
DELETE /api/v1/users/me/profile-image
```

- **인증**: 필요 (`USER`)

**응답:** `204 No Content`

---

## 4. 판매자 API

### 4.1 판매자 등록 신청

```http
POST /api/v1/seller-applications
```

- **인증**: `USER`

**요청:**

```json
{
  "brandName": "길동상점",
  "contactEmail": "seller@example.com",
  "description": "한정판 상품 판매점"
}
```

**응답:** `201 Created`

```json
{
  "success": true,
  "data": {
    "applicationId": 10,
    "brandName": "길동상점",
    "status": "PENDING",
    "appliedAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 4.2 내 판매자 신청 조회

```http
GET /api/v1/seller-applications/me
```

- **인증**: `USER`

**응답:**

```json
{
  "success": true,
  "data": {
    "applicationId": 10,
    "brandName": "길동상점",
    "contactEmail": "seller@example.com",
    "description": "한정판 상품 판매점",
    "status": "PENDING",
    "appliedAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 4.3 판매자 신청 목록 조회

```http
GET /api/v1/admin/seller-applications?status=PENDING&page=0&size=20
```

- **인증**: `ADMIN`

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "applicationId": 10,
        "userId": 1,
        "brandName": "길동상점",
        "contactEmail": "seller@example.com",
        "status": "PENDING",
        "appliedAt": "2026-09-18T14:00:00+09:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  }
}
```

### 4.4 판매자 신청 승인

```http
POST /api/v1/admin/seller-applications/{applicationId}/approve
```

- **인증**: `ADMIN`

> 승인 시 `sellers.status`를 `APPROVED`로 변경해 유효 `SELLER` 권한을 활성화한다.

**응답:**

```json
{
  "success": true,
  "data": {
    "applicationId": 10,
    "status": "APPROVED",
    "approvedAt": "2026-09-18T15:00:00+09:00"
  }
}
```

### 4.5 판매자 신청 반려

```http
POST /api/v1/admin/seller-applications/{applicationId}/reject
```

- **인증**: `ADMIN`

**요청:**

```json
{
  "reason": "사업자 정보 확인이 필요합니다."
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "applicationId": 10,
    "status": "REJECTED",
    "rejectedReason": "사업자 정보 확인이 필요합니다.",
    "rejectedAt": "2026-09-18T15:00:00+09:00"
  }
}
```

---

## 5. DROP 조회 API

### 5.1 카테고리 목록 조회

```http
GET /api/v1/categories
```

- **인증**: 불필요

**응답:**

```json
{
  "success": true,
  "data": [
    {
      "categoryId": 1,
      "code": "FASHION",
      "name": "패션"
    }
  ]
}
```

> 활성 상태인 카테고리만 표시 순서대로 반환합니다.

### 5.2 공개 DROP 목록 조회

```http
GET /api/v1/drops
```

- **인증**: 불필요

**쿼리 파라미터:**
- `status`: `WISH` | `GRAB` | `ENDED`
- `categoryId`: 카테고리 ID (예: `1`)
- `keyword`: 상품명 검색 키워드
- `soldOut`: 품절 여부 (`true` | `false`)
- `sort`: 정렬 조건 (예: `createdAt,desc`)
- `page`: 페이지 번호 (0부터 시작)
- `size`: 페이지 크기

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "dropId": 100,
        "name": "한정판 스니커즈",
        "thumbnailUrl": "https://example.com/image.jpg",
        "minPrice": 129000,
        "category": {
          "categoryId": 1,
          "name": "패션"
        },
        "status": "WISH",
        "soldOut": false,
        "wishCount": 152,
        "saleStartsAt": "2026-09-20T10:00:00+09:00",
        "saleEndsAt": "2026-09-20T12:00:00+09:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  }
}
```

> `DRAFT`와 `CANCELED` DROP은 공개 목록에서 제외합니다.

### 5.3 DROP 상세 조회

```http
GET /api/v1/drops/{dropId}
```

- **인증**: 불필요

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "name": "한정판 스니커즈",
    "description": "상품 설명",
    "imageUrls": [
      "https://example.com/image1.jpg"
    ],
    "minPrice": 129000,
    "category": {
      "categoryId": 1,
      "name": "패션"
    },
    "status": "GRAB",
    "soldOut": false,
    "wishCount": 152,
    "wishNotice": "WISH는 구매, 재고 예약 또는 구매 우선권을 보장하지 않습니다.",
    "saleStartsAt": "2026-09-20T10:00:00+09:00",
    "saleEndsAt": "2026-09-20T12:00:00+09:00",
    "shipping": {
      "shippingFee": 3000,
      "shippingNotice": "결제 완료 후 3~5 영업일 이내 출고"
    },
    "optionGroups": [
      {
        "groupId": 11,
        "name": "소재",
        "sortOrder": 0,
        "values": [
          { "valueId": 111, "value": "코튼", "sortOrder": 0 },
          { "valueId": 112, "value": "린넨", "sortOrder": 1 }
        ]
      },
      {
        "groupId": 12,
        "name": "길이",
        "sortOrder": 1,
        "values": [
          { "valueId": 121, "value": "숏", "sortOrder": 0 },
          { "valueId": 122, "value": "롱", "sortOrder": 1 }
        ]
      }
    ],
    "options": [
      {
        "optionId": 1001,
        "selections": [
          { "groupId": 11, "valueId": 111 },
          { "groupId": 12, "valueId": 122 }
        ],
        "unitPrice": 129000,
        "availableStock": 10,
        "soldOut": false
      }
    ],
    "actions": {
      "wishable": false,
      "wishCancelable": false,
      "orderable": true
    },
    "serverTime": "2026-09-20T10:10:00+09:00"
  }
}
```

> 옵션 그룹명과 값은 판매자가 자유롭게 정의합니다. 프론트엔드는 `optionGroups`를 순서대로 표시하고 선택된 값 조합과 일치하는 `options[].optionId`를 주문에 사용합니다.

---

## 6. 판매자 DROP 관리 API

> 모든 API는 `SELLER` 인증과 DROP 소유권 검증이 필요합니다.

### 6.1 DROP 임시 저장

```http
POST /api/v1/seller/drops
```

- **인증**: `SELLER`

**요청:**

```json
{
  "name": "한정판 스니커즈",
  "description": "상품 설명",
  "imageUrls": [
    "https://example.com/image1.jpg"
  ],
  "categoryId": 1,
  "saleStartsAt": "2026-09-20T10:00:00+09:00",
  "saleEndsAt": "2026-09-20T12:00:00+09:00",
  "shipping": {
    "shippingFee": 3000,
    "shippingNotice": "결제 완료 후 3~5 영업일 이내 출고"
  },
  "optionGroups": [
    {
      "key": "material",
      "name": "소재",
      "sortOrder": 0,
      "values": [
        { "key": "cotton", "value": "코튼", "sortOrder": 0 },
        { "key": "linen", "value": "린넨", "sortOrder": 1 }
      ]
    },
    {
      "key": "length",
      "name": "길이",
      "sortOrder": 1,
      "values": [
        { "key": "short", "value": "숏", "sortOrder": 0 },
        { "key": "long", "value": "롱", "sortOrder": 1 }
      ]
    }
  ],
  "options": [
    {
      "selections": [
        { "groupKey": "material", "valueKey": "cotton" },
        { "groupKey": "length", "valueKey": "short" }
      ],
      "unitPrice": 129000,
      "totalQuantity": 10,
      "active": true,
      "sortOrder": 0
    },
    {
      "selections": [
        { "groupKey": "material", "valueKey": "linen" },
        { "groupKey": "length", "valueKey": "long" }
      ],
      "unitPrice": 139000,
      "totalQuantity": 10,
      "active": true,
      "sortOrder": 1
    }
  ]
}
```

**응답:** `201 Created`

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "status": "DRAFT",
    "createdAt": "2026-09-18T14:00:00+09:00"
  }
}
```

> 임시 저장은 일부 필수 정보가 없어도 허용할 수 있으나 공개 시 전체 항목을 검증합니다. 요청의 `key`, `groupKey`, `valueKey`는 같은 요청 안에서 그룹·값·SKU를 연결하기 위한 클라이언트 키이며 저장 후 응답에서는 서버 ID를 사용합니다.

### 6.2 판매자 DROP 목록

```http
GET /api/v1/seller/drops?status=DRAFT&page=0&size=20
```

- **인증**: `SELLER`

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "dropId": 100,
        "name": "한정판 스니커즈",
        "status": "DRAFT",
        "minPrice": 129000,
        "createdAt": "2026-09-18T14:00:00+09:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  }
}
```

### 6.3 판매자 DROP 상세

```http
GET /api/v1/seller/drops/{dropId}
```

- **인증**: `SELLER`

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "name": "한정판 스니커즈",
    "description": "상품 설명",
    "imageUrls": [
      "https://example.com/image1.jpg"
    ],
    "minPrice": 129000,
    "categoryId": 1,
    "status": "DRAFT",
    "saleStartsAt": "2026-09-20T10:00:00+09:00",
    "saleEndsAt": "2026-09-20T12:00:00+09:00",
    "shipping": {
      "shippingFee": 3000,
      "shippingNotice": "결제 완료 후 3~5 영업일 이내 출고"
    },
    "optionGroups": [
      {
        "groupId": 11,
        "name": "소재",
        "sortOrder": 0,
        "values": [
          { "valueId": 111, "value": "코튼", "sortOrder": 0 },
          { "valueId": 112, "value": "린넨", "sortOrder": 1 }
        ]
      },
      {
        "groupId": 12,
        "name": "길이",
        "sortOrder": 1,
        "values": [
          { "valueId": 121, "value": "숏", "sortOrder": 0 },
          { "valueId": 122, "value": "롱", "sortOrder": 1 }
        ]
      }
    ],
    "options": [
      {
        "optionId": 1001,
        "selections": [
          { "groupId": 11, "valueId": 111 },
          { "groupId": 12, "valueId": 121 }
        ],
        "unitPrice": 129000,
        "totalQuantity": 10,
        "reservedQuantity": 0,
        "soldQuantity": 0,
        "active": true,
        "sortOrder": 0
      }
    ]
  }
}
```

### 6.4 DRAFT DROP 수정

```http
PATCH /api/v1/seller/drops/{dropId}
```

- **인증**: `SELLER`

> 요청 본문은 생성 API와 동일하며 변경할 필드만 전달합니다.

**오류 코드:**
- `DROP_NOT_FOUND`
- `DROP_ACCESS_DENIED`
- `DROP_NOT_EDITABLE`
- `INVALID_SCHEDULE`

### 6.5 DROP 공개

```http
POST /api/v1/seller/drops/{dropId}/publish
```

- **인증**: `SELLER`

**상태 전이:**
- `DRAFT` → `WISH`

**공개 전 검증:**
- 상품명, 설명, 이미지, 카테고리, 배송 정보 필수
- 판매 시작·종료 시각 필수
- 시작 시각은 종료 시각보다 이전
- 옵션 그룹명은 DROP 안에서 중복될 수 없음
- 그룹 안의 옵션값은 중복될 수 없음
- 각 SKU는 모든 그룹에서 정확히 하나의 값을 선택해야 함
- 동일한 값 조합의 SKU는 중복될 수 없음
- 각 SKU에 0 이상의 가격과 재고가 필요함
- 활성 상태이며 재고가 1개 이상인 SKU가 최소 하나 필요함

> 옵션이 없는 상품은 `optionGroups`를 빈 배열로 보내고 값 선택이 없는 `기본` SKU 한 개를 등록합니다.

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "status": "WISH",
    "publishedAt": "2026-09-18T16:00:00+09:00"
  }
}
```

### 6.6 WISH DROP 취소

```http
POST /api/v1/seller/drops/{dropId}/cancel
```

- **인증**: `SELLER`

**요청:**

```json
{
  "reason": "상품 공급 일정 변경"
}
```

**상태 전이:**
- `WISH` → `CANCELED`

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "status": "CANCELED",
    "canceledAt": "2026-09-19T10:00:00+09:00"
  }
}
```

### 6.7 판매자 재고 현황 조회

```http
GET /api/v1/seller/drops/{dropId}/stocks
```

- **인증**: `SELLER`

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "options": [
      {
        "optionId": 1001,
        "optionName": "코튼 / 롱",
        "totalStock": 10,
        "availableStock": 4,
        "reservedStock": 2,
        "soldStock": 4
      }
    ]
  }
}
```

---

## 7. WISH API

### 7.1 WISH 등록

```http
PUT /api/v1/drops/{dropId}/wish
```

- **인증**: 필요 (`USER`)

> 동일한 요청을 반복해도 활성 WISH는 하나만 존재합니다.

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "wished": true,
    "wishedAt": "2026-09-18T14:00:00+09:00",
    "notice": "WISH는 구매, 재고 예약 또는 구매 우선권을 보장하지 않습니다."
  }
}
```

**오류 코드:**
- `DROP_NOT_FOUND`
- `DROP_NOT_WISHABLE`
- `GRAB_ALREADY_STARTED`

### 7.2 WISH 취소

```http
DELETE /api/v1/drops/{dropId}/wish
```

- **인증**: 필요 (`USER`)

**응답:** `204 No Content`

> GRAB 시작 이후에는 취소할 수 없습니다.

### 7.3 내 WISH 목록

```http
GET /api/v1/users/me/wishes?page=0&size=20
```

- **인증**: 필요 (`USER`)

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "dropId": 100,
        "name": "한정판 스니커즈",
        "thumbnailUrl": "https://example.com/image.jpg",
        "minPrice": 129000,
        "status": "WISH",
        "wishedAt": "2026-09-18T14:00:00+09:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  }
}
```

### 7.4 DROP 활성 WISH 수 조회

```http
GET /api/v1/seller/drops/{dropId}/wish-count
```

- **인증**: `SELLER` (소유권 필요)

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "activeWishCount": 152
  }
}
```

---

## 8. 주문 API

### 8.1 주문 생성 및 재고 확보

```http
POST /api/v1/orders
```

- **인증**: 필요 (`USER`)
- **헤더**: `Idempotency-Key: {UUID}`

**요청:**

```json
{
  "dropId": 100,
  "items": [
    {
      "optionId": 1001,
      "quantity": 2
    }
  ],
  "shippingAddress": {
    "recipient": "홍길동",
    "phone": "01012345678",
    "postalCode": "06236",
    "address1": "서울특별시 강남구 테헤란로 1",
    "address2": "101호"
  }
}
```

> 클라이언트는 가격이나 총결제금액을 전달하지 않습니다.

**처리 조건:**
- 서버 시각 기준 DROP 상태가 `GRAB`이어야 한다.
- 판매 시작 이후, 종료 이전이어야 한다.
- 요청한 모든 옵션에 충분한 가용 재고가 있어야 한다.
- 재고 확보와 주문 생성은 하나의 트랜잭션으로 처리한다.
- 결제 마감 시각은 주문 생성 시점부터 10분으로 설정한다.

**응답:** `201 Created`

```json
{
  "success": true,
  "data": {
    "orderId": 500,
    "orderNumber": "ORD-20260918-000500",
    "status": "PAYMENT_PENDING",
    "items": [
      {
        "optionId": 1001,
        "productName": "한정판 스니커즈",
        "optionName": "코튼 / 롱",
        "unitPrice": 129000,
        "quantity": 2,
        "subtotal": 258000
      }
    ],
    "itemsAmount": 258000,
    "shippingFee": 3000,
    "totalAmount": 261000,
    "paymentExpiresAt": "2026-09-18T14:10:00+09:00"
  }
}
```

**오류 코드:**
- `DROP_NOT_ON_SALE`
- `SALE_NOT_STARTED`
- `SALE_ENDED`
- `OPTION_NOT_FOUND`
- `INSUFFICIENT_STOCK`
- `DUPLICATE_IDEMPOTENCY_KEY`

### 8.2 내 주문 목록

```http
GET /api/v1/orders?status=PAID&page=0&size=20
```

- **인증**: 필요 (`USER`)

> 본인 주문만 반환합니다.

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "orderId": 500,
        "orderNumber": "ORD-20260918-000500",
        "status": "PAID",
        "totalAmount": 261000,
        "orderedAt": "2026-09-18T14:00:00+09:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  }
}
```

### 8.3 내 주문 상세

```http
GET /api/v1/orders/{orderId}
```

- **인증**: 필요 (`USER`)

> 응답에는 주문 당시 스냅샷 정보를 반환합니다.

**응답:**

```json
{
  "success": true,
  "data": {
    "orderId": 500,
    "orderNumber": "ORD-20260918-000500",
    "status": "PAID",
    "items": [
      {
        "productName": "한정판 스니커즈",
        "optionName": "코튼 / 롱",
        "unitPrice": 129000,
        "quantity": 2,
        "subtotal": 258000
      }
    ],
    "totalAmount": 261000,
    "paymentStatus": "SUCCEEDED",
    "shipping": {
      "status": null,
      "carrier": null,
      "trackingNumber": null
    },
    "orderedAt": "2026-09-18T14:00:00+09:00"
  }
}
```

### 8.4 주문 취소

```http
POST /api/v1/orders/{orderId}/cancel
```

- **인증**: 필요 (`USER`)
- **헤더**: `Idempotency-Key: {UUID}`

**요청:**

```json
{
  "reason": "단순 변심"
}
```

**처리 규칙:**
- 본인 주문만 취소할 수 있다.
- `PAYMENT_PENDING` 주문은 즉시 취소하고 확보 재고를 반환한다.
- `PAID` 주문은 PG 결제 취소 성공 후 `CANCELED`로 전환한다.
- `PREPARING` 이후 주문은 소비자가 직접 취소할 수 없다.
- 재고 반환은 한 번만 수행한다.
- 반환 재고는 가용 재고(`AVAILABLE`)로 복구한다.

**오류 코드:**
- `ORDER_NOT_FOUND`
- `ORDER_ACCESS_DENIED`
- `ORDER_NOT_CANCELABLE`
- `PAYMENT_CANCEL_FAILED`
- `ORDER_STATUS_CONFLICT`

---

## 9. 결제 API

### 9.1 Mock 결제 요청

```http
POST /api/v1/orders/{orderId}/payments
```

- **인증**: 주문 소유자 (`USER`)
- **헤더**: `Idempotency-Key: {UUID}`

**요청:**

```json
{
  "paymentMethod": "MOCK_CARD",
  "mockResult": "SUCCESS"
}
```

- `mockResult`: `SUCCESS` | `FAILURE` | `TIMEOUT`

**응답:**

```json
{
  "success": true,
  "data": {
    "paymentId": 700,
    "orderId": 500,
    "orderNumber": "ORD-20260918-000500",
    "amount": 261000,
    "status": "SUCCEEDED",
    "paidAt": "2026-09-18T14:03:00+09:00"
  }
}
```

**검증 항목:**
- 주문번호 일치 여부
- 서버에 저장된 결제 금액과 PG 결과 금액의 일치 여부
- 주문이 `PAYMENT_PENDING` 상태인지 여부
- 결제 유효시간이 지나지 않았는지 여부

### 9.2 Mock PG 결제 결과 수신

```http
POST /api/v1/payments/mock/webhook
```

- **인증**: PG 검증 (Webhook Secret / Signature)

**요청:**

```json
{
  "eventId": "evt-1234",
  "paymentKey": "mock-payment-key",
  "orderNumber": "ORD-20260918-000500",
  "amount": 261000,
  "status": "SUCCEEDED",
  "occurredAt": "2026-09-18T14:03:00+09:00"
}
```

**처리 규칙:**
- `eventId` 또는 `paymentKey`로 중복 처리를 방지한다.
- 결제 금액과 주문번호를 서버 데이터와 비교한다.
- 결제 성공 시 확보 재고를 판매 완료 재고로 확정한다.
- 만료 이후 성공 결과는 주문을 자동 완료하지 않고 결제를 `UNKNOWN`, 보정 상태를 `REQUIRED`로 기록한다.

### 9.3 주문 결제 이력 조회

```http
GET /api/v1/orders/{orderId}/payments
```

- **인증**: 주문 소유자 또는 해당 주문의 판매자

**응답:**

```json
{
  "success": true,
  "data": [
    {
      "paymentId": 700,
      "orderId": 500,
      "paymentMethod": "MOCK_CARD",
      "amount": 261000,
      "status": "SUCCEEDED",
      "paidAt": "2026-09-18T14:03:00+09:00"
    }
  ]
}
```

---

## 10. 판매자 주문 및 배송 API

### 10.1 판매자 주문 목록 조회

```http
GET /api/v1/seller/orders
```

- **인증**: `SELLER`

**쿼리 파라미터:**
- `dropId`: DROP ID (예: `100`)
- `orderStatus`: `PAID` 등
- `paymentStatus`: `SUCCEEDED` 등
- `page`: 페이지 번호
- `size`: 페이지 크기

> 자신이 등록한 DROP의 주문만 반환합니다.

### 10.2 판매자 주문 상세 조회

```http
GET /api/v1/seller/orders/{orderId}
```

- **인증**: `SELLER`

> 해당 주문에 포함된 DROP의 소유권을 검증합니다.

### 10.3 배송 준비 처리

```http
POST /api/v1/seller/orders/{orderId}/prepare-shipment
```

- **인증**: `SELLER`

**상태 전이:**
- `PAID` → `PREPARING`

**응답:**

```json
{
  "success": true,
  "data": {
    "orderId": 500,
    "status": "PREPARING"
  }
}
```

### 10.4 배송 정보 등록 및 발송 처리

```http
POST /api/v1/seller/orders/{orderId}/shipment
```

- **인증**: `SELLER`
- **헤더**: `Idempotency-Key: {UUID}`

**요청:**

```json
{
  "carrier": "CJ대한통운",
  "trackingNumber": "123456789012",
  "shippedAt": "2026-09-19T10:00:00+09:00"
}
```

**상태 전이:**
- `PREPARING` → `SHIPPED`

> 취소 처리와 동시에 요청된 경우 하나의 상태 전이만 성공해야 합니다.

### 10.5 배송 완료 처리

```http
POST /api/v1/seller/orders/{orderId}/delivery-complete
```

- **인증**: `SELLER`

**상태 전이:**
- `SHIPPED` → `DELIVERED`

**응답:**

```json
{
  "success": true,
  "data": {
    "orderId": 500,
    "status": "DELIVERED"
  }
}
```

---

## 11. 판매자 대시보드 API

### 11.1 대시보드 요약

```http
GET /api/v1/seller/dashboard/summary
```

- **인증**: `SELLER`

**쿼리 파라미터:**
- `from`: 시작 일시 (예: `2026-09-01T00:00:00+09:00`)
- `to`: 종료 일시 (예: `2026-09-30T23:59:59+09:00`)

**응답:**

```json
{
  "success": true,
  "data": {
    "dropCounts": {
      "DRAFT": 2,
      "WISH": 3,
      "GRAB": 1,
      "ENDED": 5,
      "CANCELED": 1
    },
    "orderCounts": {
      "PAYMENT_PENDING": 4,
      "PAID": 10,
      "PREPARING": 3,
      "SHIPPED": 7,
      "CANCELED": 2
    },
    "paymentCounts": {
      "SUCCEEDED": 20,
      "FAILED": 3,
      "UNKNOWN": 1
    },
    "reconciliationRequired": 1,
    "stockSummary": {
      "available": 100,
      "reserved": 10,
      "sold": 50
    }
  }
}
```

### 11.2 DROP별 통계

```http
GET /api/v1/seller/dashboard/drops?page=0&size=20
```

- **인증**: `SELLER`

**응답:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "dropId": 100,
        "name": "한정판 스니커즈",
        "status": "GRAB",
        "activeWishCount": 152,
        "availableStock": 4,
        "reservedStock": 2,
        "soldStock": 14,
        "orderCount": 10,
        "salesAmount": 1806000
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  }
}
```

### 11.3 임박 DROP 조회

```http
GET /api/v1/seller/dashboard/upcoming-drops?withinMinutes=60
```

- **인증**: `SELLER`

> 시작 또는 종료까지 지정한 시간 이하로 남은 DROP을 반환합니다.

---

## 12. 재고 조회 API

### 12.1 SSE 재고 구독 (MVP 이후)

> MVP에서는 아래 SSE 엔드포인트를 구현하지 않습니다. 재고 재조회만으로 시작하고 실제 필요성과 부하를 확인한 뒤 도입합니다.

```http
GET /api/v1/drops/{dropId}/stock-stream
Accept: text/event-stream
```

- **인증**: 불필요

**이벤트 예시:**

```http
event: stock-changed
id: 10001
data: {"dropId":100,"optionId":1001,"availableStock":8,"soldOut":false,"occurredAt":"2026-09-20T10:01:00+09:00"}
```

**전체 품절 이벤트:**

```http
event: drop-sold-out
id: 10002
data: {"dropId":100,"soldOut":true,"occurredAt":"2026-09-20T10:05:00+09:00"}
```

> 도입 이후에도 실시간 데이터는 사용자 표시용이며 실제 주문 가능 여부는 주문 API가 서버의 최신 재고를 기준으로 다시 판단합니다.

### 12.2 현재 재고 재조회

```http
GET /api/v1/drops/{dropId}/stocks
```

- **인증**: 불필요

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "options": [
      {
        "optionId": 1001,
        "availableStock": 8,
        "soldOut": false
      }
    ],
    "serverTime": "2026-09-20T10:01:00+09:00"
  }
}
```

---

## 13. 이미지 업로드 API

### 13.1 이미지 업로드 URL 발급

```http
POST /api/v1/uploads/images/presigned-url
```

- **인증**: `SELLER`

**요청:**

```json
{
  "fileName": "shoes.jpg",
  "contentType": "image/jpeg",
  "fileSize": 1048576
}
```

**응답:**

```json
{
  "success": true,
  "data": {
    "uploadUrl": "https://storage.example.com/presigned-url",
    "imageUrl": "https://cdn.example.com/images/uuid.jpg",
    "expiresAt": "2026-09-18T14:10:00+09:00"
  }
}
```

---

## 14. 주요 오류 코드

| 오류 코드 | HTTP 상태 | 설명 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | 요청 형식 오류 |
| `VALIDATION_FAILED` | 400 | 필드 검증 실패 |
| `AUTHENTICATION_REQUIRED` | 401 | 인증 필요 |
| `INVALID_TOKEN` | 401 | 유효하지 않은 토큰 |
| `ACCESS_DENIED` | 403 | 권한 없음 |
| `RESOURCE_NOT_FOUND` | 404 | 리소스 없음 |
| `DUPLICATE_EMAIL` | 409 | 이메일 중복 |
| `INVALID_STATE_TRANSITION` | 409 | 허용되지 않은 상태 전이 |
| `DROP_NOT_EDITABLE` | 409 | 수정할 수 없는 DROP |
| `DROP_NOT_WISHABLE` | 409 | WISH 불가능 상태 |
| `DROP_NOT_ON_SALE` | 409 | 판매 상태가 아님 |
| `DUPLICATE_OPTION_COMBINATION` | 409 | 동일한 옵션값 조합의 SKU 중복 |
| `ORDER_NOT_CANCELABLE` | 409 | 취소할 수 없는 주문 |
| `ORDER_STATUS_CONFLICT` | 409 | 주문 상태 동시 변경 충돌 |
| `PAYMENT_ALREADY_PROCESSED` | 409 | 이미 처리된 결제 |
| `INSUFFICIENT_STOCK` | 422 | 재고 부족 |
| `INVALID_OPTION_COMBINATION` | 422 | 옵션 그룹·값·SKU 조합 검증 실패 |
| `PAYMENT_AMOUNT_MISMATCH` | 422 | 결제 금액 불일치 |
| `PAYMENT_EXPIRED` | 422 | 결제 유효시간 만료 |
| `INVALID_SCHEDULE` | 422 | 판매 일정 오류 |

---

## 15. 백그라운드 처리 규칙

1. 서버 시각이 `saleStartsAt`에 도달하면 `WISH` → `GRAB`으로 전환한다.
2. 서버 시각이 `saleEndsAt`에 도달하면 `GRAB` → `ENDED`로 전환한다.
3. 결제 대기 시간이 만료되면 주문을 `EXPIRED`로 전환한다.
4. 만료된 주문의 확보 재고를 한 번만 반환한다.
5. 모든 옵션의 `availableStock`이 0이면 `soldOut=true`로 처리한다.
6. 결제 성공 웹훅이 만료 후 도착하면 자동 완료하지 않고 보정 대상으로 기록한다.

---

## 16. 멱등성 적용 대상

다음 요청은 `Idempotency-Key`를 지원합니다.

- `POST /api/v1/orders`
- `POST /api/v1/orders/{orderId}/payments`
- `POST /api/v1/orders/{orderId}/cancel`
- `POST /api/v1/seller/orders/{orderId}/shipment`

> 동일 키와 동일 요청 본문이 다시 전달되면 최초 처리 결과를 반환합니다. 동일 키에 서로 다른 요청 본문이 전달되면 `409 Conflict`를 반환합니다.
> 요청 본문이 있는 멱등 요청은 SHA-256 `request_hash`를 함께 저장합니다.
>
> Mock PG 웹훅은 `Idempotency-Key` 헤더 대신 PG가 전달한 `eventId`를 고유 키로 사용합니다.

---

## 17. 전체 API 목록 요약

### 17.1 회원 및 인증

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

### 17.2 판매자 등록 및 승인

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/v1/seller-applications` | USER | 일반 회원이 판매자 등록 신청 |
| GET | `/api/v1/seller-applications/me` | USER | 본인의 판매자 신청 상태 조회 |
| GET | `/api/v1/admin/seller-applications` | ADMIN | 판매자 신청 목록을 상태별로 조회 |
| POST | `/api/v1/admin/seller-applications/{applicationId}/approve` | ADMIN | 판매자 신청 승인 및 SELLER 기능 활성화 |
| POST | `/api/v1/admin/seller-applications/{applicationId}/reject` | ADMIN | 판매자 신청 반려 및 사유 기록 |

### 17.3 공개 DROP 탐색

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

### 17.4 판매자 DROP 관리

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

### 17.5 WISH

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| PUT | `/api/v1/drops/{dropId}/wish` | USER | WISH 상태의 DROP에 WISH 등록 |
| DELETE | `/api/v1/drops/{dropId}/wish` | USER | GRAB 시작 전 본인의 WISH 취소 |
| GET | `/api/v1/users/me/wishes` | USER | 본인이 등록한 WISH 목록 조회 |

> `PUT` 방식을 사용하므로 같은 사용자가 동일 DROP에 요청을 반복해도 활성 WISH는 한 개만 유지합니다.

### 17.6 주문 및 소비자 마이페이지

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

### 17.7 결제

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/v1/orders/{orderId}/payments` | USER | 결제 대기 주문에 대해 Mock 결제 실행 |
| GET | `/api/v1/orders/{orderId}/payments` | USER/SELLER | 주문에 대한 결제 시도 및 결과 이력 조회 |
| POST | `/api/v1/payments/mock/webhook` | PG 검증 | Mock PG에서 전달한 결제 결과 수신 |

> 결제 결과 수신 시 주문번호, 금액, 결제 상태, 결제 유효시간을 검증합니다. 중복 통지가 도착해도 주문과 재고는 한 번만 변경합니다.

### 17.8 판매자 주문 및 배송 관리

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| GET | `/api/v1/seller/orders` | SELLER | 본인 DROP에 접수된 주문 목록 조회 |
| GET | `/api/v1/seller/orders/{orderId}` | SELLER | 본인 DROP의 주문 상세 조회 |
| POST | `/api/v1/seller/orders/{orderId}/prepare-shipment` | SELLER | 결제 완료 주문을 배송 준비 상태로 변경 |
| POST | `/api/v1/seller/orders/{orderId}/shipment` | SELLER | 택배사와 송장번호를 등록하고 발송 처리 |
| POST | `/api/v1/seller/orders/{orderId}/delivery-complete` | SELLER | 배송 중인 주문을 배송 완료 처리 |

> 판매자는 자신이 생성한 DROP의 주문만 조회하거나 변경할 수 있습니다.

### 17.9 판매자 대시보드

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| GET | `/api/v1/seller/dashboard/summary` | SELLER | DROP·주문·결제·재고 상태별 건수 조회 |
| GET | `/api/v1/seller/dashboard/drops` | SELLER | DROP별 WISH·주문·판매·재고 통계 조회 |
| GET | `/api/v1/seller/dashboard/upcoming-drops` | SELLER | 시작 또는 종료가 임박한 DROP 조회 |

### 17.10 이미지 업로드

| Method | Endpoint | 인증 | 용도 |
| --- | --- | --- | --- |
| POST | `/api/v1/uploads/images/presigned-url` | SELLER | 상품 이미지 업로드용 Presigned URL 발급 |

> 발급된 URL로 스토리지에 이미지를 직접 업로드하고, 반환된 이미지 주소를 DROP 생성·수정 요청에 사용합니다.

---

## 18. 전체 API 간단 요약

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

## 19. 권한 표기 기준

| 표기 | 설명 |
| --- | --- |
| 불필요 | 비회원도 호출 가능 |
| USER | 로그인한 일반 회원 이상 |
| SELLER | 승인된 판매자 권한 필요 |
| ADMIN | 운영자 권한 필요 |
| USER/SELLER | 주문 소유자 또는 해당 DROP의 판매자 |
| PG 검증 | 사용자 토큰 대신 Webhook 서명 또는 별도 Secret 검증 |
