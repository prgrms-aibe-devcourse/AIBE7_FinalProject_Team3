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

> 활성 상태인 카테고리만 id 순(시드 등록 순)으로 반환합니다.

### 1.2 공개 DROP 목록 조회

```http
GET /api/v1/drops
```

- **인증**: 불필요

**쿼리 파라미터:**

| 파라미터 | 허용 값 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `status` | `WISH` \| `GRAB` \| `ENDED` | 전체 | 없으면 세 상태를 모두 반환합니다. |
| `categoryId` | 정수 | 전체 | 카테고리 ID (예: `1`) |
| `keyword` | 문자열 (최대 100자) | 전체 | 상품명 부분 일치 검색 |
| `soldOut` | `true` \| `false` | 전체 | 품절 여부 |
| `sort` | `publishedAt` \| `saleStartsAt` \| `createdAt` + `,asc`\|`,desc` | `publishedAt,desc` | 정렬 조건 (예: `createdAt,desc`) |
| `page` | 0 이상 정수 | `0` | 페이지 번호 (0부터 시작) |
| `size` | 1 ~ 100 | `20` | 페이지 크기 |

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
>
> - `status`에 `WISH`·`GRAB`·`ENDED` 외의 값(예: `DRAFT`, `CANCELED`)을 주면 거부합니다.
> - `keyword`는 앞뒤 공백을 제거하고, 비어 있으면 검색 조건을 무시합니다. 상품명을 대소문자 구분 없이 부분 일치로 찾고, `%`·`_`·`\`는 와일드카드가 아니라 리터럴로 처리합니다. 100자를 넘으면 거부합니다.
> - `soldOut`은 활성 옵션(`is_active = true`)의 가용 재고(`total - reserved - sold - withheld`) 합이 0이면 `true`입니다. 활성 옵션이 하나도 없으면 `true`입니다. DROP 상태가 `GRAB`이어도 재고가 없으면 `true`입니다.
> - `minPrice`는 활성 SKU(`is_active = true`) 중 최저 `unitPrice`이며, 활성 SKU가 없으면 `null`입니다(2.2와 같은 규칙).
> - `wishCount`는 취소되지 않은(`canceled_at IS NULL`) WISH 수입니다.
> - `thumbnailUrl`은 `sort_order`가 가장 작은 이미지이며, 이미지가 없으면 `null`입니다.
> - 정렬 값이 같으면 `id` 내림차순으로 정렬합니다. `minPrice`·`wishCount` 정렬은 지원하지 않습니다.
>
> `status`는 저장된 DROP 상태 기준입니다. 판매 시작 시각(`saleStartsAt`)이 지나도 전환 배치(GR-18)가 실행되기 전까지 `WISH`로 보일 수 있으며, 이때 WISH 등록·취소는 `GRAB_ALREADY_STARTED`(409)로 거부됩니다.

**오류 코드:**
- `INVALID_REQUEST` — `page`가 0 미만, `size`가 1 미만 또는 100 초과, 허용되지 않은 `status`·`sort` 값, 숫자가 아닌 `categoryId`, `keyword` 100자 초과

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
>
> `minPrice`·`soldOut`·`wishCount`는 1.2와 같은 규칙입니다. `minPrice`는 활성 SKU 최저 `unitPrice`(없으면 `null`), `soldOut`은 활성 옵션의 가용 재고 합이 0이면 `true`, `wishCount`는 취소되지 않은(`canceled_at IS NULL`) WISH 수입니다.
> `options`에는 활성 SKU(`is_active = true`)만 포함하고, 각 SKU의 `soldOut`은 `availableStock == 0`입니다. `optionGroups`·`values`는 활성 개념 없이 전부 반환합니다.
> 없는 DROP과 `DRAFT`는 `DROP_NOT_FOUND`로 숨깁니다. `CANCELED`는 200으로 보여 주고 `actions`가 모두 `false`입니다.
> `wishNotice`는 WISH 안내 문구이고, `serverTime`은 `actions`를 판정한 서버 시각입니다.
>
> `actions`는 비로그인 API이므로 사용자와 무관하게 DROP 상태·시각으로만 정합니다.
> `orderable`은 저장 상태가 아니라 서버 시각(`serverTime`)과 판매 기간으로 판정합니다. 시작 전환 배치(GR-18)가 아직 실행되지 않아 저장 상태가 `WISH`여도 `saleStartsAt <= now < saleEndsAt`이면 `true`입니다.
>
> | DROP 상태·시각 | `wishable` | `wishCancelable` | `orderable` |
> | --- | --- | --- | --- |
> | `WISH`, 현재 시각 < `saleStartsAt` | true | true | false |
> | `WISH`, 판매 기간 내(`saleStartsAt <= now < saleEndsAt`), 품절 아님 | false | false | true |
> | `WISH`, 현재 시각 ≥ `saleEndsAt` (전환 배치 이전) | false | false | false |
> | `GRAB`, 판매 기간 내, 품절 아님 | false | false | true |
> | `WISH`/`GRAB`, 품절 또는 현재 시각 ≥ `saleEndsAt` | false | false | false |
> | `ENDED`, `CANCELED` | false | false | false |
>
> `orderable`은 주문 생성 검증(공개 상태 `WISH`·`GRAB` + 판매 시각 범위)과 같은 규칙이며 품절이면 `false`입니다.

**오류 코드:**
- `DROP_NOT_FOUND` — 없는 DROP, `DRAFT`
- `INVALID_REQUEST` — 숫자가 아닌 `dropId`

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

**오류 코드:**
- `VALIDATION_FAILED` — 존재하지 않거나 비활성인 카테고리(`fieldErrors`의 `field`는 `categoryId`)

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
        "thumbnailUrl": "https://example.com/image.jpg",
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

> `minPrice`는 활성 SKU(`is_active = true`) 중 최저 `unitPrice`이며, 활성 SKU가 없으면 `null`입니다. 정렬은 `id` 내림차순(최근 생성 순)입니다.
> `thumbnailUrl`은 `sort_order`가 가장 작은 이미지이며, 이미지가 없으면 `null`입니다. 이미지를 등록하지 않은 DRAFT는 `null`이 흔합니다.

**오류 코드:**
- `INVALID_REQUEST` — `page`가 0 미만, `size`가 1 미만 또는 100 초과, 잘못된 `status` 값

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

> `minPrice`는 목록과 같은 규칙(활성 SKU 중 최저 `unitPrice`, 활성 SKU가 없으면 `null`)입니다. 옵션 그룹·값·SKU와 각 `selections`는 `sortOrder` 순으로 반환합니다.

**오류 코드:**
- `DROP_NOT_FOUND`
- `DROP_ACCESS_DENIED`

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
- `VALIDATION_FAILED` — 존재하지 않거나 비활성인 카테고리(`fieldErrors`의 `field`는 `categoryId`)

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

**오류 코드:**
- `DROP_NOT_FOUND`
- `DROP_ACCESS_DENIED`
- `INVALID_STATE_TRANSITION` — DRAFT가 아닌 DROP
- `VALIDATION_FAILED` — 필수 항목 누락(누락된 항목 전부를 `fieldErrors`로 반환), 존재하지 않거나 비활성인 카테고리(`field`는 `categoryId`)
- `INVALID_SCHEDULE` — 시작 시각이 종료 시각보다 이전이 아님
- `INVALID_OPTION_COMBINATION` — 그룹명·옵션값 중복, 그룹별 선택 누락, 음수 가격·재고, 판매 가능한 SKU 없음
- `DUPLICATE_OPTION_COMBINATION` — 동일한 값 조합의 SKU 중복

> 옵션 검증 실패 시 `fieldErrors`의 `field`로 위반 위치(예: `options[1]`, `optionGroups[0]`)를 알려줍니다.

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

- `reason`은 `@NotBlank`이며 최대 500자입니다. 원문은 로그에 남기지 않습니다.

**상태 전이:**
- `WISH` → `CANCELED`

**취소 조건:**
- `status == WISH && now < saleStartsAt`일 때만 취소할 수 있습니다.
- 저장 상태가 아직 `WISH`여도 판매 시작 시각이 지났으면 취소할 수 없습니다.
- `now`는 DROP 행 잠금(`SELECT ... FOR UPDATE`)을 획득한 뒤 생성하므로, 잠금 대기 중 판매가 시작되면 취소가 거부됩니다.
- 취소 시 `closedAt`, `closeReason = SELLER_CANCELED`, `cancelReason`을 함께 기록합니다. 기존 WISH 행은 수정하지 않습니다.

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

**오류 코드:**
- `DROP_NOT_FOUND` — 없는 DROP
- `DROP_ACCESS_DENIED` — 다른 판매자의 DROP
- `INVALID_STATE_TRANSITION` — `WISH`가 아니거나 판매 시작 시각이 지난 DROP
- `VALIDATION_FAILED` — `reason` 누락·공백·500자 초과

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

