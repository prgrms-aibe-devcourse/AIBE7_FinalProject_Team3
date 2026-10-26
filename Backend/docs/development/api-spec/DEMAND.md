# 수요 분석 API 명세

> 요구사항: [REQUIREMENTS.md](../REQUIREMENTS.md) 1.13 (DEMAND-001~009), NFR-012·013
> 스키마: [ERD.md](../ERD.md) 1.7 (`demand_plans`, `drop_embeddings`)
> 선정 근거: [TECHSTACK.md](../TECHSTACK.md) 1.3

판매자의 WISH DROP에 대해 수요 데이터를 집계하고 판매 계획을 계산한다. 계산은 서버의 결정적 함수가 하고, LLM은 저장된 계획의 설명만 만든다. 제안은 참고용이며 재고·가격·판매 상태를 바꾸지 않는다.

## 1. 계산 규칙

### 1.1 수요 데이터

| 값 | 정의 |
| --- | --- |
| `activeWishCount` | 해당 DROP의 `canceled_at IS NULL`인 WISH 수 |
| `dailyWishes` | 최근 14일의 날짜(KST)별 활성 WISH 등록 수. 재등록 시 `activated_at`을 덮어쓰므로 근사치다(`trendApproximate: true`). |
| `hoursUntilSaleStart` | 서버 시각부터 `sale_starts_at`까지 남은 시간(시간 단위, 내림) |

### 1.2 전환율 표본 선택

후보는 `status = ENDED`, `close_reason = TIME_EXPIRED`이고 활성 WISH가 1 이상인 DROP이며 분석 대상 DROP은 제외한다. 후보를 본인 DROP과 다른 판매자 DROP으로 나눠 두 기준의 전환율을 따로 계산하고, 섞지 않는다.

| 전환율 기준 | 표본 | 표본 수 |
| --- | --- | --- |
| `OWN` (내 실적) | 본인의 후보 DROP | 최대 20개 |
| `MARKET` (시장 평균) | 다른 판매자의 후보 DROP 중 `closed_at`이 오늘 0시(KST) 이전인 DROP(1.3) | 최대 20개 |

각 기준 안에서 표본은 아래 방법으로 고른다.

| 기준 | 선택 방법 | 사용 조건 |
| --- | --- | --- |
| `SIMILAR` | 대상과 후보의 임베딩 코사인 유사도가 0.75 이상인 DROP을 유사도 순으로 선택 | 대상 DROP의 임베딩이 있고(GR-147), 해당 기준의 결과 신뢰도가 `NONE`이 아닐 때 |
| `CATEGORY` | 같은 `category_id`의 DROP을 `closed_at` 최신순으로 선택 | 그 외 모든 경우(GR-147 구현 전 기본값) |

유사도 기준값은 설정(`grab.demand.similarity.min-score`)으로 바꾼다.

### 1.3 다른 판매자 데이터 보호

다른 판매자의 DROP별 판매 실적은 노출하지 않는다(DEMAND-008). 내 실적은 본인 데이터이므로 정확한 값과 표본 수를 그대로 보여 주고, 시장 평균에만 아래 규칙을 적용한다.

- **최소 집계 단위**: 시장 표본이 DROP 10개 이상이고 서로 다른 판매자가 5명 이상일 때만 시장 평균을 계산한다. 미달이면 시장 평균의 신뢰도는 `NONE`이고 값은 `null`이다.
- **기준 시점 고정**: 시장 표본은 오늘 0시(KST) 이전에 종료된 DROP으로 제한해 하루 동안 값이 바뀌지 않게 한다. 별도 스냅샷 테이블·배치 없이 조회 조건으로 처리하고, 기준 시점을 `market.asOf`로 반환한다.
- **반올림**: 시장 평균 전환율은 정수 %(소수 둘째 자리, 예: 0.30), 구매자당 평균 수량은 소수 첫째 자리로 반올림한 값을 계산과 응답에 쓴다. 전환율 구간(1.4)은 원래 집계값으로 계산한 뒤 같은 자릿수로 하한은 내림, 상한은 올림한다.
- **구간 표시**: 시장 표본 수는 구간(`NONE`, `10_TO_19`, `20_PLUS`)으로만, 표본 WISH 수는 10 단위 내림으로 표시한다.
- **기준 분리**: 본인 DROP을 시장 표본에 넣지 않는다. 본인 값과 섞은 합산값을 보여 주면 판매자가 자기 실적을 빼서 다른 판매자 값을 역산할 수 있기 때문이다.
- 응답·저장 스냅샷·LLM 입력에는 집계값만 담는다. 다른 판매자의 상품명·브랜드·DROP ID·DROP별 수치는 담지 않는다.

**한계**: 날짜별 값을 비교해 그사이 종료된 DROP의 실적을 추정하는 차분 공격은 위 규칙으로 어려워지지만 완전히 막히지 않는다. 하루에 종료된 시장 DROP이 하나뿐이면 반올림 후에도 일부 정보가 드러날 수 있다. 통계에 잡음을 섞는 차분 프라이버시는 현재 규모에 비해 과하다고 보고 적용하지 않는다.

### 1.4 전환율

- 표본 DROP마다 `wishers` = 활성 WISH 수, `converted` = 그중 해당 DROP에 결제 확정(`paid_at` 있음)·미취소(`canceled_at` 없음) 주문이 한 건 이상 있는 사용자 수, `quantity` = 그 주문들의 `order_items.quantity` 합계.
- 기준(`OWN`, `MARKET`)마다 `conversionRate` = Σ`converted` / Σ`wishers`, `avgQuantityPerBuyer` = Σ`quantity` / Σ`converted` (Σ`converted`가 0이면 1)를 따로 계산한다. 내 실적의 전환율은 소수 둘째 자리, 구매자당 평균 수량은 소수 첫째 자리로 반올림한 값을 계산과 응답에 쓴다. 시장 평균(1.3)과 같은 정밀도라 두 기준을 나란히 비교할 수 있고, 응답에 보이는 값으로 계산하므로 판매자가 화면 수치로 검산한 결과와 LLM 설명의 수치 검증(3절) 대상이 계산 결과와 일치한다.
- WISH 없이 구매한 사용자는 포함하지 않는다. 예상 수량이 실제보다 낮게 나올 수 있다는 점을 응답의 `notes`에 표시한다.

**전환율 구간**: 기준마다 전환율의 95% 윌슨 점수 구간(`rateLow`, `rateHigh`)을 계산하고, 예상 범위(1.6)는 이 구간으로 정한다. 하한은 소수 둘째 자리 내림, 상한은 올림해 범위가 좁아지지 않게 한다.

```text
p = Σconverted / Σwishers,  n = Σwishers,  z = 1.96
center = (p + z²/2n) / (1 + z²/n)
half   = z × √(p(1-p)/n + z²/4n²) / (1 + z²/n)
rateLow = center - half,  rateHigh = center + half
```

- 고정 폭(예: ±20%) 대신 표본 크기로 범위를 정한다. 같은 전환율 0.30이어도 WISH 20명이면 0.14~0.52, 100명이면 0.21~0.40, 2,000명이면 0.28~0.33처럼 표본이 많을수록 좁아진다.
- 윌슨 구간은 단순 정규근사(p ± z√(p(1-p)/n))와 달리 표본이 작거나 전환율이 0·1에 가까워도 0~1을 벗어나지 않고, 구매자가 0명인 표본에서도 상한이 0보다 크다.
- 한계: WISH 사용자를 서로 독립인 시행으로 보므로, 상품마다 전환율이 다른 차이(DROP 간 분산)는 반영하지 않는다. 실제 불확실성은 구간보다 클 수 있으며 1.9의 백테스트로 보정한다. 구매자당 평균 수량의 불확실성도 범위에 넣지 않는다.

**신뢰도**: 기준마다 따로 판정하며 범위 폭이 아니라 계산 여부와 기본 기준 선택(1.5)에 쓴다. 시장 평균은 1.3의 최소 집계 단위에 미달하면 `NONE`이다.

| 신뢰도 | 조건 | 용도 |
| --- | --- | --- |
| `HIGH` | 표본 DROP 10개 이상, Σ`wishers` 100 이상 | 기본 기준 선택 시 우선 |
| `LOW` | 표본 DROP 3개 이상, Σ`wishers` 20 이상 | 계산하되 `SMALL_CONVERSION_SAMPLE` 표시 |
| `NONE` | 그 외 | 계산하지 않음 |

### 1.5 계획에 쓸 전환율 기준

| `assumptions.conversionRate.source` | 사용하는 값 | 선택 방법 |
| --- | --- | --- |
| `OWN` | 내 실적의 전환율·구매자당 평균 수량 | 판매자가 `conversionBasis: OWN` 지정, 또는 기본값 |
| `MARKET` | 시장 평균의 전환율·구매자당 평균 수량 | 판매자가 `conversionBasis: MARKET` 지정, 또는 기본값 |
| `SELLER_INPUT` | 판매자가 입력한 전환율, 구매자당 평균 수량은 기본 기준의 값(없으면 1) | 판매자가 `conversionRate` 입력 |

- 기본값은 신뢰도가 높은 기준이다(`HIGH` > `LOW`). 같으면 `OWN`을 쓰고, 둘 다 `NONE`이면 기준이 없다.
- 판매자가 신뢰도 `NONE`인 기준을 지정하면 범위·추천 수량 없이 `NO_CONVERSION_SAMPLE`을 반환한다.

### 1.6 판매 계획

```text
base        = activeWishCount × conversionRate × avgQuantityPerBuyer
low         = floor(activeWishCount × rateLow × avgQuantityPerBuyer)
expected    = round(base)
high        = ceil(activeWishCount × rateHigh × avgQuantityPerBuyer)
recommended = ceil(base × (1 + safetyStockRate))
```

- 기본 가정: `conversionRate`·`avgQuantityPerBuyer`는 1.5의 기본 기준 값(기준이 없으면 없음), `safetyStockRate`는 0.1.
- `rateLow`·`rateHigh`는 사용한 기준의 전환율 구간(1.4)이다. 판매자가 전환율을 직접 입력하면 표본이 없어 통계 구간을 만들 수 없으므로 `rateLow = conversionRate × 0.7`, `rateHigh = min(conversionRate × 1.3, 1)`을 쓴다(1.9).
- 반올림 방향은 값의 역할에 따라 다르다. `expected`는 판매량 예측이라 반올림해 위·아래 어느 쪽으로도 치우치지 않게 하고(실제 판매량과의 오차 측정에 사용), `low`·`high`는 범위가 좁아지지 않게 각각 내림·올림한다. `recommended`는 준비 수량이라 올림해 소수점 아래 수요 때문에 모자라지 않게 한다. 생산·발주 단위(최소 생산 수량, 묶음 단위) 맞춤은 범위 밖이며 판매자가 결정을 기록할 때(2.6) 반영한다.
- 입력 범위: `0 < conversionRate ≤ 1`, `0 ≤ safetyStockRate ≤ 1`, 각각 소수 둘째 자리까지. 벗어나면 `VALIDATION_FAILED`.
- 옵션별 배분: `recommended`를 활성 옵션(`is_active = true`)의 현재 `total_quantity` 비율로 나눈다. 정수 배분은 최대 나머지 방식이며 나머지가 같으면 `sort_order`, `id` 순이다.
- 현재 판매 수량 합계(`currentTotalQuantity`)와 차이(`differenceFromCurrent`)를 함께 반환한다.
- 계산 로직을 바꾸면 `calculationVersion`을 올린다(초기 `v1`, 유사 상품 기준 도입 시 `v2`).

### 1.7 데이터 부족

| `dataGaps` 코드 | 조건 | 결과 |
| --- | --- | --- |
| `NO_ACTIVE_WISH` | `activeWishCount = 0` | 범위·추천 수량 없음 |
| `NO_CONVERSION_SAMPLE` | 사용할 기준이 없거나 지정한 기준의 신뢰도가 `NONE`이고, 판매자 입력 전환율 없음 | 범위·추천 수량 없음 |
| `SMALL_CONVERSION_SAMPLE` | 사용한 기준의 신뢰도 `LOW` | 계산하고 추천 수량도 제공. 표본이 적어 범위가 넓다는 점을 알린다 |

범위·추천 수량이 없으면 `plan`의 수량 필드가 `null`이다. 판매자는 다른 기준을 고르거나 전환율을 직접 입력해 다시 계산할 수 있다.

### 1.8 제안 표시 조건

`suggestion.ready`는 `activeWishCount ≥ 30` 또는 `hoursUntilSaleStart ≤ 48`일 때 `true`다. 기준값은 설정(`grab.demand.suggestion.*`)으로 바꾼다.

### 1.9 설정값의 근거와 보정

계산식의 입력(WISH 수, 구매자 수, 수량, 옵션 비율)은 실측값이고, 전환율 구간은 통계식(1.4)으로 정한다. 아래 값은 실측으로 검증하지 않은 초기 가정이며, 근거와 보정 방법을 함께 둔다.

| 값 | 초기값 | 근거 | 성격 |
| --- | --- | --- | --- |
| 구간 신뢰수준 | 95% (`z = 1.96`) | 통계에서 가장 널리 쓰는 기준 | 관행 |
| 신뢰도 `LOW` 기준 | DROP 3개·WISH 20명 | 상품 하나의 특성에 좌우되지 않을 최소 DROP 수와, 구매자 한두 명 차이로 전환율이 크게 흔들리지 않을 최소 인원 | 가정 |
| 신뢰도 `HIGH` 기준 | DROP 10개·WISH 100명 | 전환율 0.30에서 구간 폭이 약 ±0.09로 줄어드는 인원과 여러 상품이 섞이는 DROP 수 | 가정 |
| 판매자 입력 범위 | ±30% | 표본이 없는 판단 값이라 통계 구간을 만들 수 없어 둔 폭 | 가정 |
| 안전 재고율 | 10% | 재고 관리에서 흔히 쓰는 출발값. 판매자가 0~100%로 바꾼다 | 관행 |
| 표본 최대 DROP 수 | 20개 | 오래된 판매 결과가 섞이지 않게 최근 결과만 사용 | 가정 |
| 시장 평균 최소 집계 단위 | 판매자 5명·DROP 10개 | 개별 판매자가 식별되지 않게 하는 최소 집계 단위(k-익명성) | 보호 관행 |

**보정 방법**: 종료된 DROP이 쌓이면 각 DROP의 판매 시작 시점 데이터로 계획을 다시 계산하고 실제 판매량과 비교하는 백테스트를 한다. 실제 판매량이 `low`~`high` 안에 든 비율(목표 약 95%)과 `expected`의 평균 절대 백분율 오차(MAPE)를 기준별로 기록하고, 구간 적중률이 목표보다 낮으면 DROP 간 분산을 반영한 구간으로 바꾸거나 신뢰도 기준을 올린다. 평가 기록은 GR-146의 평가 지표와 함께 관리하며, 값을 바꾸면 `calculationVersion`을 올린다.

## 2. 판매자 수요 분석 API

모든 API는 `SELLER` 권한과 DROP 소유권을 검사한다. 분석·저장·결정 대상은 `status = WISH`이고 서버 시각이 `sale_starts_at` 이전인 DROP이며, 그 외에는 `DEMAND_ANALYSIS_UNAVAILABLE`(409)이다.

### 2.1 수요 분석 조회

```http
GET /api/v1/seller/drops/{dropId}/demand-analysis
```

- **인증**: `SELLER`

기본 가정으로 계산한 결과를 반환한다.

**응답:**

```json
{
  "success": true,
  "data": {
    "dropId": 100,
    "calculatedAt": "2026-10-22T14:00:00+09:00",
    "calculationVersion": "v1",
    "suggestion": { "ready": true },
    "demand": {
      "activeWishCount": 420,
      "hoursUntilSaleStart": 40,
      "dailyWishes": [
        { "date": "2026-10-21", "count": 35 },
        { "date": "2026-10-22", "count": 12 }
      ],
      "trendApproximate": true
    },
    "conversion": {
      "selection": "CATEGORY",
      "own": {
        "rate": 0.4,
        "rateLow": 0.3,
        "rateHigh": 0.51,
        "avgQuantityPerBuyer": 1.0,
        "confidence": "LOW",
        "dropCount": 3,
        "wishCount": 85
      },
      "market": {
        "rate": 0.3,
        "rateLow": 0.27,
        "rateHigh": 0.33,
        "avgQuantityPerBuyer": 1.0,
        "confidence": "HIGH",
        "dropCountBand": "10_TO_19",
        "wishCount": 1740,
        "asOf": "2026-10-22T00:00:00+09:00"
      }
    },
    "assumptions": {
      "conversionRate": { "value": 0.3, "source": "MARKET" },
      "avgQuantityPerBuyer": { "value": 1.0, "source": "MARKET" },
      "safetyStockRate": { "value": 0.1, "source": "DEFAULT" }
    },
    "plan": {
      "low": 113,
      "expected": 126,
      "high": 139,
      "recommended": 139,
      "currentTotalQuantity": 120,
      "differenceFromCurrent": 19,
      "options": [
        { "optionId": 1001, "name": "블랙 / M", "currentQuantity": 40, "recommendedQuantity": 47 },
        { "optionId": 1002, "name": "블랙 / L", "currentQuantity": 40, "recommendedQuantity": 46 },
        { "optionId": 1003, "name": "화이트 / M", "currentQuantity": 40, "recommendedQuantity": 46 }
      ]
    },
    "dataGaps": [],
    "notes": ["WISH 없이 구매한 사용자는 전환율에 포함하지 않았습니다."]
  }
}
```

- `conversion.own`·`conversion.market`: 두 기준을 항상 함께 반환한다. `rateLow`·`rateHigh`는 전환율 구간(1.4)이다. 신뢰도가 `NONE`이면 `rate`·`rateLow`·`rateHigh`·`avgQuantityPerBuyer`가 `null`이다. `market`은 1.3의 보호 규칙을 적용한 집계값이다.
- `assumptions.*.source`: `OWN`(내 실적), `MARKET`(시장 평균), `DEFAULT`(기본값), `SELLER_INPUT`(판매자 입력). 사용할 기준이 없으면 `conversionRate`·`avgQuantityPerBuyer`는 `null`이다.
- 옵션 `name`은 옵션 그룹 순서대로 값을 ` / `로 이은 표시명이다.

### 2.2 가정 변경 재계산

```http
POST /api/v1/seller/drops/{dropId}/demand-analysis/calculate
```

- **인증**: `SELLER`

**요청:**

```json
{
  "conversionBasis": "OWN",
  "safetyStockRate": 0.05
}
```

- `conversionBasis`: 선택, `OWN` 또는 `MARKET`. 생략하면 1.5의 기본 기준을 쓴다.
- `conversionRate`: 선택, 판매자가 직접 정한 전환율. `conversionBasis`와 함께 보내면 `VALIDATION_FAILED`.
- `safetyStockRate`: 선택, 생략하면 기본값.

응답은 2.1과 같고 저장하지 않는다.

### 2.3 판매 계획 저장

```http
POST /api/v1/seller/drops/{dropId}/demand-plans
```

- **인증**: `SELLER`

요청은 2.2와 같다. 서버가 저장 시점 데이터로 다시 계산해 `DRAFT`로 저장하고, 커밋 후 LLM 설명 생성을 비동기로 시작한다(3절).

**응답:** `201 Created`, 본문은 2.5의 계획 상세(`explanationStatus: PENDING`)

### 2.4 판매 계획 목록

```http
GET /api/v1/seller/drops/{dropId}/demand-plans?page=0&size=20
```

- **인증**: `SELLER`

최근 저장순으로 계획 요약(`planId`, `status`, `recommended`, `createdAt`, `decidedAt`)을 페이지 응답으로 반환한다. 이 API는 DROP 상태와 관계없이 조회할 수 있다.

### 2.5 판매 계획 상세

```http
GET /api/v1/seller/demand-plans/{planId}
```

- **인증**: `SELLER`

**응답:**

```json
{
  "success": true,
  "data": {
    "planId": "4f0c9a52-1d6b-4f4e-9a57-8f6d2c1e0b11",
    "dropId": 100,
    "status": "DRAFT",
    "createdAt": "2026-10-22T14:05:00+09:00",
    "analysis": { "...": "2.1 data와 같은 형식의 저장 시점 스냅샷" },
    "decision": null,
    "explanationStatus": "GENERATED",
    "explanation": {
      "summary": "활성 WISH 420명에 같은 카테고리 시장 평균 전환율 30%를 적용하면 예상 주문 수량은 126개입니다.",
      "reasons": [
        "내 실적 전환율 40%는 표본 DROP 3개로 신뢰도가 낮아, 신뢰도가 높은 시장 평균을 기준으로 했습니다.",
        "내 실적이 시장 평균보다 높으므로 내 실적 기준으로 다시 계산해 비교할 수 있습니다."
      ],
      "risks": ["WISH 없이 구매한 사용자가 많으면 예상보다 빨리 품절될 수 있습니다."],
      "dataGaps": [],
      "notRecommended": ["예상 수량 126개만 준비하면 예상 범위 상단 139개에 미치지 못합니다."]
    }
  }
}
```

- `decision`: 결정 후 `{ "status", "quantities", "reason", "decidedAt" }`.
- `explanationStatus`가 `GENERATED`가 아니면 `explanation`은 `null`이다. `PENDING`이 생성 후 2분을 넘으면 응답에서 `FAILED`로 표시한다(저장값은 바꾸지 않음).

### 2.6 판매 계획 결정 기록

```http
POST /api/v1/seller/demand-plans/{planId}/decision
```

- **인증**: `SELLER`

**요청:**

```json
{
  "status": "MODIFIED",
  "quantities": [
    { "optionId": 1001, "quantity": 45 },
    { "optionId": 1002, "quantity": 40 },
    { "optionId": 1003, "quantity": 45 }
  ],
  "reason": "재고 부담을 줄이기 위해 조정"
}
```

- `status`: `ACCEPTED`, `MODIFIED`, `REJECTED`
- `quantities`: `MODIFIED`일 때 필수, 계획의 모든 옵션을 한 번씩 포함하고 각 값은 0 이상. 다른 상태에서는 보내지 않는다.
- `reason`: 선택, 최대 500자
- 이미 결정된 계획은 `DEMAND_PLAN_ALREADY_DECIDED`(409), 판매 시작 이후는 `DEMAND_ANALYSIS_UNAVAILABLE`(409)
- 결정은 DROP의 판매 수량을 바꾸지 않는다.

**응답:** 2.5의 계획 상세

## 3. LLM 설명 생성

- 입력: 저장된 계산 결과의 집계값과 코드값만 보낸다(`result`의 수치·신뢰도·표본 구간, 계산에 사용한 `assumptions`). 회원 정보, 원본 행, 다른 판매자의 개별 데이터는 보내지 않는다(DEMAND-007, NFR-012).
- 상품명·카테고리명·옵션 표시명처럼 판매자가 입력한 텍스트는 보내지 않는다. 프롬프트 주입 경로를 없애고, 옵션명 속 숫자(예: 250mm)가 수치 검증을 방해하지 않게 하기 위해서다. 옵션은 배분 순서 번호(옵션 1, 2, …)로 전달하고 화면에서 표시명과 짝지어 보여 준다.
- 출력: `summary`(문자열), `reasons`·`risks`·`dataGaps`·`notRecommended`(문자열 배열)의 JSON.
- 수치 검증: 출력 문자열의 모든 숫자가 입력의 수치(정수, 소수, 백분율 환산값)와 같은지 대조한다. 하나라도 다르면 `DISCARDED`로 기록하고 응답 원문은 저장하지 않는다.
- 호출 시간 제한 10초, 재시도하지 않는다. 실패하면 `FAILED`로 기록한다. 계획 저장·조회·결정은 설명과 관계없이 동작한다.
- 모델명·프롬프트 버전·토큰 사용량을 계획에 기록한다.

## 4. 지표

| 지표 | 태그 | 용도 |
| --- | --- | --- |
| `grab.demand.plan.saved` | `selection`, `basis`(`own`, `market`, `seller_input`, `none`) | 계획 저장 수, 표본·전환율 기준별 비율 |
| `grab.demand.plan.decided` | `status` | 수락·수정·거절 비율 |
| `grab.demand.explanation` | `outcome`(`generated`, `discarded`, `failed`) | 설명 생성 결과 |
| `grab.demand.explanation.duration` | `model` | LLM 응답 시간 |
| `grab.demand.explanation.tokens` | `model` | 토큰 사용량 |
| `grab.demand.embedding` | `outcome`(`created`, `skipped`, `failed`) | DROP 임베딩 생성 결과(GR-147) |

평가셋 실행 결과는 지표가 아니라 버전별 기록 파일로 남긴다(GR-146). 카테고리 기준과 유사 상품 기준의 예측 오차 비교도 같은 방식으로 기록한다(GR-147).
