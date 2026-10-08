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
| `sort` | `publishedAt` \| `saleStartsAt` \| `createdAt` + `,asc`\|`,desc`; `wishCount,desc`; `soldQuantity,desc` | `publishedAt,desc` | 인기 정렬은 아래 상태 조합만 허용합니다. |
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
> - `thumbnailUrl`은 GALLERY의 첫 이미지(`sort_order = 0`)이며, 이미지가 없으면 `null`입니다. DETAIL은 썸네일에 사용하지 않습니다.
> - 날짜 정렬 값이 같으면 `id` 내림차순으로 정렬합니다. 인기 정렬은 페이지를 자르기 전에 전체 후보를 DB에서 집계·정렬합니다.
>
> `status`는 저장된 DROP 상태 기준입니다. 판매 시작 시각(`saleStartsAt`)이 지나도 전환 배치(GR-18)가 실행되기 전까지 `WISH`로 보일 수 있으며, 이때 WISH 등록·취소는 `GRAB_ALREADY_STARTED`(409)로 거부됩니다.

인기 정렬은 예외적으로 서버 시각의 실제 탐색 단계로 후보를 정합니다. `status=WISH&sort=wishCount,desc`는 저장 상태 WISH이면서 `now < saleStartsAt`인 상품의 활성 WISH(`wishes.canceled_at IS NULL`) 수를 사용합니다. `status=GRAB&sort=soldQuantity,desc`는 저장 상태 WISH 또는 GRAB이며 `saleStartsAt <= now < saleEndsAt`인 상품 중 활성 SKU의 가용 재고가 1개 이상인 상품만 반환합니다. 취소·종료·비공개 상품은 제외합니다. GRAB 점수는 `paid_at IS NOT NULL AND canceled_at IS NULL`인 주문의 `order_items.quantity` 합계이며 미결제·만료·취소 주문과 결제 시도 횟수는 포함하지 않습니다. 점수가 같으면 두 정렬 모두 `publishedAt DESC, id DESC`를 적용하고 0건 상품도 포함합니다. 해당 인기 정렬은 지정한 상태 조합에서만 허용하며 `status` 생략·다른 상태·오름차순은 거부합니다. 메인은 각각 `page=0&size=8`, 목록은 `size=24`로 조회하며, `categoryId`·`keyword`·`soldOut` 필터는 기존대로 적용합니다. 서버 시각은 조회 시 한 번 고정하고 상태 전환 배치 지연과 무관하게 판정합니다. 응답의 `status`는 기존처럼 저장 상태를 표시하므로 인기 GRAB 결과가 전환 지연 중에는 `WISH`일 수 있습니다. 화면은 조회 영역과 상세 `actions`를 기준으로 구매 동작을 결정합니다.

**오류 코드:**
- `INVALID_REQUEST` — `page`가 0 미만, `size`가 1 미만 또는 100 초과, 허용되지 않은 `status`·`sort` 값·인기 정렬/상태 조합, 숫자가 아닌 `categoryId`, `keyword` 100자 초과

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
    "galleryImages": [
      { "imageUrl": "https://example.com/image1.jpg", "altText": "한정판 스니커즈 정면" }
    ],
    "detailImages": [
      { "imageUrl": "https://example.com/detail1.jpg", "altText": "신발의 소재와 크기" }
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
> `galleryImages`와 `detailImages`는 용도별 `sort_order` 순으로 반환하고 이미지가 없으면 빈 배열입니다. 기존 `imageUrls`는 GALLERY URL만 같은 순서로 반환해 구버전 소비자 화면과 호환합니다. 상세 설명 텍스트는 `description`이고 DETAIL 이미지는 그 아래에 표시합니다.
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
  "images": [
    {
      "imageId": "1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b",
      "imageUrl": "https://<project>.supabase.co/storage/v1/object/public/drop-images/images/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.jpg",
      "altText": "한정판 스니커즈 정면"
    }
  ],
  "detailImages": [
    {
      "imageId": "2d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b",
      "imageUrl": "https://<project>.supabase.co/storage/v1/object/public/drop-images/images/2d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.jpg",
      "altText": "신발의 소재와 크기"
    }
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
>
> `images`는 기존 계약과 같은 GALLERY 배열, `detailImages`는 선택적인 DETAIL 배열입니다. 원소는 이미지 업로드 URL 발급(IMAGE_UPLOAD.md 1.1)에서 받은 `{ imageId, imageUrl }`에 선택적인 `altText`를 더한 객체입니다. 구버전 요청의 `images`만 보내는 방식은 계속 허용합니다.
> - 생성에서 누락/null은 빈 용도 배열입니다. DRAFT 수정에서 각 필드의 누락/null은 해당 용도를 유지하고, `[]`는 해당 용도만 전부 삭제하며, 비어 있지 않은 배열은 해당 용도 전체를 순서대로 교체합니다. 한 용도만 수정해도 다른 용도는 유지됩니다.
> - 상품 이미지는 두 용도를 합쳐 최대 10개입니다. 공개 시 GALLERY가 최소 한 장 필요하고 DETAIL은 선택입니다.
> - `sort_order`는 각 배열 인덱스(0부터)입니다. `altText`는 최대 300자이며 빈 문자열은 장식 이미지에 사용합니다. 기존 이미지의 `altText`를 누락/null로 보내면 저장된 설명을 유지하고 새 이미지에서 누락/null이면 상품명(없으면 빈 문자열)을 사용합니다.
> - `imageUrl`은 `imageId`와의 관계를 검증합니다. 정확히 `{공개 URL 접두사}images/{imageId}.{jpg|png|webp}` 형식이어야 하며, 다른 사이트 URL이나 다른 이미지의 URL은 거부합니다.
> - 두 용도 전체에서 `imageId`가 중복되면 거부합니다. 같은 DROP의 이미지를 다른 용도로 옮길 때는 두 배열을 함께 보내고 기존 용도에서 제거합니다.
> - 다른 DROP이 이미 사용 중인 `imageId`는 거부합니다. 같은 DROP의 이미지를 다시 보내는 수정은 허용하며 `public_id`는 유지됩니다.

**오류 코드:**
- `VALIDATION_FAILED` — 존재하지 않거나 비활성인 카테고리(`fieldErrors`의 `field`는 `categoryId`), 두 용도 합계 이미지 10개 초과·원소 누락(null), URL 형식 불일치·`altText` 300자 초과, 두 용도 사이 중복 또는 다른 DROP이 사용 중인 `imageId` (이미지 오류의 `field`는 `images[i]` 또는 `detailImages[i]`로 시작)

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
> `thumbnailUrl`은 GALLERY의 첫 이미지이며, GALLERY가 없는 DRAFT는 `null`입니다. DETAIL은 썸네일에 사용하지 않습니다.

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
    "images": [
      {
        "imageId": "1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b",
        "imageUrl": "https://<project>.supabase.co/storage/v1/object/public/drop-images/images/1d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.jpg",
        "altText": "한정판 스니커즈 정면"
      }
    ],
    "detailImages": [
      {
        "imageId": "2d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b",
        "imageUrl": "https://<project>.supabase.co/storage/v1/object/public/drop-images/images/2d0f9e8c-7b6a-4539-8412-6c5d4e3f2a1b.jpg",
        "altText": "신발의 소재와 크기"
      }
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
> `images`는 GALLERY, `detailImages`는 DETAIL의 `{ imageId, imageUrl, altText }` 객체를 용도별 `sort_order` 순으로 반환하며 없는 용도는 `[]`입니다. 수정 화면은 받은 값을 다시 보낼 수 있습니다. 구버전 판매자 화면은 `images`만 사용해도 DETAIL을 삭제하지 않습니다. 공개 상세(1.3)·공개 목록(1.2)은 기존 `imageUrls`·`thumbnailUrl` 형식을 유지합니다.

**오류 코드:**
- `DROP_NOT_FOUND`
- `DROP_ACCESS_DENIED`

### 2.4 DRAFT DROP 수정

```http
PATCH /api/v1/seller/drops/{dropId}
```

- **인증**: `SELLER`

> 요청 본문은 생성 API와 동일하며 변경할 필드만 전달합니다. `images`·`detailImages` 각각의 누락/null/빈 배열 의미와 `imageId`·`imageUrl`·`altText` 검증 규칙은 2.1과 같습니다.

**오류 코드:**
- `DROP_NOT_FOUND`
- `DROP_ACCESS_DENIED`
- `DROP_NOT_EDITABLE`
- `INVALID_SCHEDULE`
- `VALIDATION_FAILED` — 존재하지 않거나 비활성인 카테고리(`fieldErrors`의 `field`는 `categoryId`), 이미지 10개 초과·원소 누락(null), `imageUrl` 형식 불일치(`images[i].imageUrl`), `imageId` 중복·타 DROP 사용(`images[i].imageId`)

### 2.5 DROP 공개

```http
POST /api/v1/seller/drops/{dropId}/publish
```

- **인증**: `SELLER`

**상태 전이:**
- `DRAFT` → `WISH`

**공개 전 검증:**
- 상품명, 설명, GALLERY 이미지 1장 이상, 카테고리, 배송 정보 필수. DETAIL은 선택
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

- **인증**: `SELLER` (승인된 판매자, DROP 소유권 필요)
- 재고 값은 PostgreSQL에 커밋된 값을 조회 시점 기준으로 반환한다. 응답 이후 재고가 유지된다고 보장하지 않는다.

**정책:**
- 소유자면 DRAFT를 포함해 상태와 관계없이 조회할 수 있다.
- 비활성 SKU도 운영 현황 확인을 위해 포함한다.
- 옵션은 SKU `sortOrder` 순서로 반환하고, 반환할 SKU가 없으면 `options: []`다.
- `availableStock = totalStock - reservedStock - soldStock - withheldQuantity`. 보류 수량은 별도 필드로 노출하지 않으며 판매 수량에 합치지 않는다.

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

- `optionName`은 값 매핑을 그룹 `sortOrder` 순으로 정렬한 뒤 선택값을 ` / `로 연결한다. 값 매핑이 없는 기본 SKU는 `기본`이다.

**오류 코드:**
- `DROP_NOT_FOUND` — 없는 DROP
- `DROP_ACCESS_DENIED` — 다른 판매자의 DROP

---
