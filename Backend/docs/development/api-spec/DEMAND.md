# 수요 분석 API 명세

> 요구사항: [REQUIREMENTS.md](../REQUIREMENTS.md) 1.13 (DEMAND-001~012), NFR-012·013
> 스키마: [ERD.md](../ERD.md) 1.7 (`demand_analysis_snapshots`, `drop_stock_recommendations`, `drop_embeddings`)
> 선정 근거: [TECHSTACK.md](../TECHSTACK.md) 1.3

판매자의 WISH DROP에 대해 수요 데이터를 집계하고 판매 계획을 계산해 참고용으로 보여 준다(1~4절). DROP을 만들 때는 종료 DROP의 판매 실적으로 재고를 추천하고 가격대를 참고하게 한다(5절). 계산은 서버의 결정적 함수가 하고, LLM은 계산 결과의 설명만 만든다. 제안은 참고용이며 재고·가격·판매 상태를 바꾸지 않는다. 판매자가 계획을 저장하거나 결정을 기록하지 않고(GR-175), 예측 정확도는 서버가 자동으로 남기는 기록(2.3, 5.6)으로 평가한다.

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

- **최소 집계 단위**: 시장 표본이 DROP 10개 이상이고 서로 다른 판매자가 5명 이상일 때만 시장 평균을 계산한다. 미달이면 시장 평균의 신뢰도는 `NONE`이고 값은 `null`이다. 이때 표본 수 구간은 `NONE`, 표본 WISH 수는 `null`로 두어 미달 표본의 규모도 드러내지 않고, `asOf`만 반환한다. 판단은 최대 표본 수(1.2)로 고른 최근 표본 안에서 한다.
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
- 반올림 방향은 값의 역할에 따라 다르다. `expected`는 판매량 예측이라 반올림해 위·아래 어느 쪽으로도 치우치지 않게 하고(실제 판매량과의 오차 측정에 사용), `low`·`high`는 범위가 좁아지지 않게 각각 내림·올림한다. `recommended`는 준비 수량이라 올림해 소수점 아래 수요 때문에 모자라지 않게 한다. 생산·발주 단위(최소 생산 수량, 묶음 단위) 맞춤은 범위 밖이며 판매자가 재고 관리 화면에서 수량을 정할 때 반영한다.
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

**보정 방법**: 종료된 DROP이 쌓이면 판매 시작 시점 기록(2.3)을 실제 판매량과 비교하는 백테스트를 한다. 실제 판매량이 `low`~`high` 안에 든 비율(목표 약 95%)과 `expected`의 평균 절대 백분율 오차(MAPE)를 기준별로 기록하고, 구간 적중률이 목표보다 낮으면 DROP 간 분산을 반영한 구간으로 바꾸거나 신뢰도 기준을 올린다. 평가 기록은 GR-146의 평가 지표와 함께 관리하며, 값을 바꾸면 `calculationVersion`을 올린다.

## 2. 판매자 수요 분석 API

모든 API는 `SELLER` 권한과 DROP 소유권을 검사한다. 분석 대상은 `status = WISH`이고 서버 시각이 `sale_starts_at` 이전인 DROP이며, 그 외에는 `DEMAND_ANALYSIS_UNAVAILABLE`(409)이다.

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
    "initialRecommendation": {
      "recordedAt": "2026-10-20T10:00:00+09:00",
      "basis": "MARKET",
      "low": 25,
      "expected": 40,
      "high": 60,
      "recommended": 44,
      "preparedTotal": 120
    },
    "dataGaps": [],
    "notes": ["WISH 없이 구매한 사용자는 전환율에 포함하지 않았습니다."]
  }
}
```

- `conversion.own`·`conversion.market`: 두 기준을 항상 함께 반환한다. `rateLow`·`rateHigh`는 전환율 구간(1.4)이다. 신뢰도가 `NONE`이면 `rate`·`rateLow`·`rateHigh`·`avgQuantityPerBuyer`가 `null`이다. `market`은 1.3의 보호 규칙을 적용한 집계값이다.
- `assumptions.*.source`: `OWN`(내 실적), `MARKET`(시장 평균), `DEFAULT`(기본값), `SELLER_INPUT`(판매자 입력). 사용할 기준이 없으면 `conversionRate`·`avgQuantityPerBuyer`는 `null`이다.
- 옵션 `name`은 옵션 그룹 순서대로 값을 ` / `로 이은 표시명이다.
- `initialRecommendation`: 공개 시점 재고 추천 기록(5.6)의 요약이다. 생성 단계 추천(과거 판매량 기준)과 WISH 기준 계획을 화면에서 비교하는 데 쓴다. `recordedAt`은 기록 시각, `preparedTotal`은 공개 시점 준비 수량 합계다. 기록이 없으면(GR-167 이전 공개, 기록 실패) `null`이고, 기록에 기준이 없었으면 `basis`와 수량 필드가 `null`이다.

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

### 2.3 판매 시작 시점 기록

- DROP이 판매를 시작(`WISH → GRAB` 전환, DROP 상태 전환 배치)한 것이 커밋된 뒤 별도 트랜잭션에서 전환 시점 데이터와 기본 가정(1.5, 안전 재고율 기본값)으로 1.6을 다시 계산해 `demand_analysis_snapshots`(ERD.md 1.7)에 DROP당 한 건 저장한다(DEMAND-005).
- 2절의 조회 조건(판매 시작 전)과 달리 판매 시작 시각에 계산하며, `hoursUntilSaleStart`는 0이다. 판매자가 화면에서 고른 기준·가정은 저장하지 않는다.
- 상태 전환과 분리하므로 계산·저장에 실패해도 판매 시작은 그대로이고 기록만 빠진다(WARN 로그). 평가용 기록이라 재시도하지 않는다. 공개 시점 기록(5.6)과 같은 방식이다.
- 종료 후 실제 판매량과 비교(예상 범위 적중률, `expected` 오차)는 SQL로 집계하며 API로 제공하지 않는다(1.9).
- 판매자가 판매 계획을 저장하고 수락·수정·거절을 기록하던 API(`/demand-plans`)는 GR-175에서 제거했다. 결정이 재고를 바꾸지 않는 기록이라 판매자에게 효과가 없었고, 생성 단계 재고 추천(5절)과 역할이 겹쳤기 때문이다.

## 3. LLM 설명 생성

- 입력: 수요 분석 결과(2.1)의 집계값과 코드값만 보낸다(`result`의 수치·신뢰도·표본 구간, 계산에 사용한 `assumptions`). 회원 정보, 원본 행, 다른 판매자의 개별 데이터는 보내지 않는다(DEMAND-007, NFR-012).
- 상품명·카테고리명·옵션 표시명처럼 판매자가 입력한 텍스트는 보내지 않는다. 프롬프트 주입 경로를 없애고, 옵션명 속 숫자(예: 250mm)가 수치 검증을 방해하지 않게 하기 위해서다. 옵션은 배분 순서 번호(옵션 1, 2, …)로 전달하고 화면에서 표시명과 짝지어 보여 준다.
- 출력: `summary`(문자열), `reasons`·`risks`·`dataGaps`·`notRecommended`(문자열 배열)의 JSON.
- 수치 검증: 출력 문자열의 모든 숫자가 입력의 수치(정수, 소수, 백분율 환산값)와 같은지 대조한다. 하나라도 다르면 `DISCARDED`로 기록하고 응답 원문은 저장하지 않는다.
- 호출 시간 제한 10초, 재시도하지 않는다. 실패하면 `FAILED`로 기록한다. 분석 조회는 설명과 관계없이 동작한다.
- 설명은 분석 결과에 붙인다. 생성 시점과 보관 방식(분석 조회 시 생성, 같은 입력은 재사용)은 GR-146에서 정한다. 판매 계획 저장이 없어져(GR-175) 계획에 기록하던 방식은 쓰지 않는다.

## 4. 지표

| 지표 | 태그 | 용도 |
| --- | --- | --- |
| `grab.demand.explanation` | `outcome`(`generated`, `discarded`, `failed`) | 설명 생성 결과 |
| `grab.demand.explanation.duration` | `model` | LLM 응답 시간 |
| `grab.demand.explanation.tokens` | `model` | 토큰 사용량 |
| `grab.demand.embedding` | `outcome`(`created`, `skipped`, `failed`) | DROP 임베딩 생성 결과(GR-147) |

평가셋 실행 결과는 지표가 아니라 버전별 기록 파일로 남긴다(GR-146). 카테고리 기준과 유사 상품 기준의 예측 오차 비교도 같은 방식으로 기록한다(GR-147).

## 5. 생성 단계 재고 추천·가격대 참고 (GR-167)

DROP을 만들거나 DRAFT를 편집할 때 같은 카테고리·비슷한 가격대 종료 DROP의 **실제 판매 실적**으로 재고 수량을 추천하고(DEMAND-010), 입력 가격의 위치와 가격대별 판매율을 보여 준다(DEMAND-011). 생성 단계에는 WISH가 없어 1~4절의 WISH 기반 분석을 쓸 수 없으므로 판매 실적을 근거로 한다. 1~4절의 WISH 단계 분석은 그대로 둔다. 추천은 참고용이며 판매자가 폼에 채워야 재고가 된다. 가격은 추천하지 않는다.

### 5.1 표본

후보는 `status = ENDED`, `close_reason = TIME_EXPIRED`이고 같은 `category_id`이며 옵션 준비 수량 합계가 1 이상인 DROP이다.

| 값 | 정의 |
| --- | --- |
| 대표 가격 | 옵션 `unit_price`의 준비 수량(`total_quantity`) 가중 평균, 원 단위 반올림. 요청에서는 모든 옵션의 입력 수량이 1 이상이면 가중 평균, 아니면 단순 평균을 쓴다 |
| 준비 수량 | 옵션 `total_quantity` 합계 |
| 판매량 | 옵션 `sold_quantity` 합계 |
| 품절 | 종료 시점 가용 재고(`total - reserved - sold - withheld`) 합계가 0 |

- 가격대 조건: 표본의 대표 가격이 요청 대표 가격의 0.7~1.3배다. 요청 대표 가격이 0이면 가격 조건 없이 고르고 가격대 참고(5.4)는 제공하지 않는다.
- 내 실적(`OWN`): 본인 후보 DROP을 `closed_at` 최신순으로 최대 20개.
- 시장 평균(`MARKET`): 다른 판매자 후보 DROP 중 `closed_at`이 오늘 0시(KST) 이전인 DROP을 최신순으로 최대 20개. 1.3의 보호 규칙(판매자 5명·DROP 10개 이상, 기준 시점 고정, 본인 DROP 제외, 개별 상품·수치 비노출)을 그대로 적용한다.
- 편집 중인 DRAFT는 종료 DROP이 아니므로 표본에 들어가지 않는다.

### 5.2 계산

기준(`OWN`, `MARKET`)마다 따로 계산하고 섞지 않는다.

| 값 | 정의 |
| --- | --- |
| `soldLow`·`soldMedian`·`soldHigh` | 판매량의 하위 25%·중간값·상위 25%. 정렬한 판매량의 `ceil(k × n)`번째 값(k = 0.25, 0.5, 0.75, nearest-rank) |
| `sellThroughRate` | Σ판매량 ÷ Σ준비 수량 |
| `soldOutRate` | 품절 DROP 수 ÷ 표본 DROP 수 |
| `confidence` | `HIGH`: 표본 DROP 10개 이상, `LOW`: 3개 이상, `NONE`: 그 외. 시장 평균은 보호 단위 미달이면 `NONE` |

- 정밀도: 내 실적의 비율은 소수 둘째 자리 반올림. 시장 평균의 비율은 정수 %(소수 둘째 자리)로 반올림하고, 판매량 분위수는 5개 단위로 하위는 내림·중간은 반올림·상위는 올림한다. 분위수 하나는 실제 DROP 하나의 판매량이라 그대로 보여 주면 개별 실적이 드러나기 때문이다. 시장 표본 수는 구간(`NONE`, `10_TO_19`, `20_PLUS`)으로만 표시한다.
- 기본 기준: 신뢰도가 높은 기준(`HIGH` > `LOW`), 같으면 `OWN`, 둘 다 `NONE`이면 기준 없음.
- 기준 수량: 기준의 `soldOutRate`가 0.5 이상이면 `soldHigh`, 아니면 `soldMedian`. 품절된 DROP의 판매량은 실제 수요의 하한이라 중간값이 수요를 낮게 잡기 때문이다(`FREQUENT_SOLD_OUT` 표시).
- 준비 권장 `recommended = ceil(기준 수량 × (1 + safetyStockRate))`. `safetyStockRate`는 0~1, 소수 둘째 자리까지, 기본 0.1.
- 예상 판매량 범위는 `soldLow`~`soldHigh`다.
- 옵션별 배분: 요청 옵션 순서대로 `recommended`를 나눈다. 모든 옵션의 입력 수량이 1 이상이면 그 비율, 아니면 균등하게 나눈다. 정수 배분은 최대 나머지 방식이며 나머지가 같으면 앞 옵션부터 준다(1.6과 같은 방식).
- 데이터 부족(`dataGaps`): `NO_SALES_SAMPLE`(기준 없음, 수량 필드 `null`), `SMALL_SALES_SAMPLE`(기준 신뢰도 `LOW`), `FREQUENT_SOLD_OUT`(기준 품절 비율 0.5 이상).

### 5.3 설정값의 근거

| 값 | 초기값 | 근거 | 성격 |
| --- | --- | --- | --- |
| 가격대 범위 | ±30% | 같은 상품군 안에서 가격대가 크게 다른 DROP의 판매량이 섞이지 않을 폭 | 가정 |
| 품절 보정 기준 | 품절 비율 50% | 절반 이상이 품절이면 중간값이 대부분 수요의 하한이 된다 | 가정 |
| 분위수 방식 | nearest-rank | 표본이 작아도 실제 관측값만 쓰고 보간한 가상의 수량을 만들지 않는다 | 관행 |
| 시장 분위수 단위 | 5개 | 개별 DROP 판매량이 그대로 드러나지 않게 하는 최소 반올림 | 보호 |
| 참고 가격 단위 | 1,000원 | 개별 DROP 가격이 그대로 드러나지 않게 하는 최소 반올림 | 보호 |
| 가격대 참고 표본 | 최대 60개 | 가격대 세 구간 각각에 보호 단위(10개)를 채울 수 있는 크기 | 가정 |

값은 설정(`grab.demand.stock.*`)으로 바꾼다. 공개 시점 기록(5.6)이 쌓이면 실제 판매량이 추천 범위 안에 든 비율로 보정하고, 계산을 바꾸면 `calculationVersion`(초기 `s1`)을 올린다.

### 5.4 가격대 참고

- 표본: 시장(다른 판매자) 후보 DROP 중 `closed_at`이 오늘 0시(KST) 이전인 DROP을 최신순으로 최대 60개. 가격 조건은 두지 않는다.
- 위치(`position`): 표본 대표 가격의 1/3·2/3 분위(nearest-rank)와 비교해 요청 대표 가격이 1/3 분위 이하면 `LOW`, 2/3 분위 이상이면 `HIGH`, 그 사이면 `MID`. 표본이 보호 단위(판매자 5명·DROP 10개) 미달이면 `UNKNOWN`.
- 가격대별 판매율(`bands`): 요청 대표 가격 기준 `LOWER`(0.7배 미만), `SIMILAR`(0.7~1.3배), `HIGHER`(1.3배 초과) 구간마다 판매율(정수 %)과 표본 수 구간을 준다. 구간마다 보호 단위 미달이면 그 구간의 값은 `null`이다.
- 참고 가격 범위(`typicalPriceLow`~`typicalPriceHigh`): 표본 대표 가격의 하위 25%~상위 25%(nearest-rank)를 천 원 단위로 하한은 내림, 상한은 올림한다. 표본이 보호 단위 미달이면 `null`이다. "이 카테고리 다른 판매자 DROP 가격은 보통 이 범위"라는 참고 정보이며 추천 가격이 아니다. 판매자 가격이 한 값으로 쏠리지 않도록 화면에서 "가격은 추천하지 않는다"는 안내를 함께 둔다.
- 위치 판단에 쓴 1/3·2/3 분위 가격은 응답에 넣지 않는다.

### 5.5 재고 추천 조회

```http
POST /api/v1/seller/stock-recommendations
```

- **인증**: `SELLER`

저장하지 않는다. 저장 전 DROP에서도 쓸 수 있도록 DROP ID 없이 폼 입력값을 받는다.

**요청:**

```json
{
  "categoryId": 3,
  "options": [
    { "unitPrice": 59000, "quantity": 40 },
    { "unitPrice": 59000, "quantity": 20 }
  ],
  "safetyStockRate": 0.1
}
```

- `categoryId`: 필수, 활성 카테고리. 없거나 비활성이면 `VALIDATION_FAILED`(`field`: `categoryId`).
- `options`: 필수 1~100개. `unitPrice` 필수 0 이상, `quantity` 선택 0 이상.
- `safetyStockRate`: 선택, 0~1, 소수 둘째 자리까지. 생략하면 0.1.

**응답:**

```json
{
  "success": true,
  "data": {
    "calculatedAt": "2026-10-26T18:00:00+09:00",
    "calculationVersion": "s1",
    "referencePrice": 59000,
    "own": {
      "soldLow": 32, "soldMedian": 41, "soldHigh": 55,
      "sellThroughRate": 0.82, "soldOutRate": 0.25,
      "confidence": "LOW", "dropCount": 4
    },
    "market": {
      "soldLow": 25, "soldMedian": 40, "soldHigh": 60,
      "sellThroughRate": 0.71, "soldOutRate": 0.38,
      "confidence": "HIGH", "dropCountBand": "10_TO_19",
      "asOf": "2026-10-26T00:00:00+09:00"
    },
    "basis": "MARKET",
    "recommendation": {
      "low": 25, "expected": 40, "high": 60,
      "recommended": 44, "safetyStockRate": 0.1,
      "options": [
        { "index": 0, "recommendedQuantity": 29 },
        { "index": 1, "recommendedQuantity": 15 }
      ]
    },
    "priceReference": {
      "position": "MID",
      "typicalPriceLow": 45000,
      "typicalPriceHigh": 72000,
      "bands": [
        { "band": "LOWER", "sellThroughRate": 0.78, "dropCountBand": "10_TO_19" },
        { "band": "SIMILAR", "sellThroughRate": 0.71, "dropCountBand": "20_PLUS" },
        { "band": "HIGHER", "sellThroughRate": null, "dropCountBand": "NONE" }
      ]
    },
    "dataGaps": []
  }
}
```

- `own`·`market`은 항상 함께 반환한다. 신뢰도 `NONE`이면 수치 필드는 `null`이고, 시장 평균은 `asOf`만 남긴다.
- `recommendation.expected`는 기준 수량(5.2), `options[].index`는 요청 옵션 순서다. 기준이 없으면 `basis`와 `recommendation`의 수량 필드가 `null`이다.
- `priceReference`: 요청 대표 가격이 0이면 `position`은 `UNKNOWN`, 참고 가격 범위는 `null`, `bands`는 빈 배열이다.

### 5.6 공개 시점 기록

- DROP 공개(`POST /api/v1/seller/drops/{dropId}/publish`, DROP.md 2.5)가 커밋된 뒤 별도 트랜잭션에서 DROP의 카테고리·옵션 가격·준비 수량으로 5.2를 다시 계산해 `drop_stock_recommendations`(ERD.md 1.7)에 DROP당 한 건 저장한다(DEMAND-012).
- 판매자가 편집 중에 본 값이 아니라 공개 시점 값이다. 화면에서 "추천 수량 채우기"를 눌렀는지는 받지 않고, 옵션별 실제 준비 수량이 추천 배분과 모두 같으면 추천을 적용한 것으로 기록한다.
- 공개 트랜잭션과 분리하므로 계산·저장에 실패해도 공개는 그대로이고 기록만 빠진다(WARN 로그). 서버가 커밋 직후 종료되면 기록이 빠질 수 있다. 평가용 기록이라 재시도하지 않는다.
- 실제 판매량과의 비교(추천 범위 적중률 등)는 종료 후 SQL로 집계하며 API로 제공하지 않는다.
