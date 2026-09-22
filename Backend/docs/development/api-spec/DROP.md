# DROP API 명세

## 1. DROP 조회 API

### 1.1 카테고리 목록 조회

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

### 1.2 공개 DROP 목록 조회

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

### 1.3 DROP 상세 조회

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

## 2. 판매자 DROP 관리 API

> 모든 API는 `SELLER` 인증과 DROP 소유권 검증이 필요합니다.

### 2.1 DROP 임시 저장

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

### 2.2 판매자 DROP 목록

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

### 2.3 판매자 DROP 상세

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

### 2.4 DRAFT DROP 수정

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

### 2.5 DROP 공개

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

### 2.6 WISH DROP 취소

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

### 2.7 판매자 재고 현황 조회

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

